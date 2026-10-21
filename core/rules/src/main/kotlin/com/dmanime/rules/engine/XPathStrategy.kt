package com.dmanime.rules.engine

import com.dmanime.rules.error.RuleFormatException
import com.dmanime.rules.model.AnimeRule
import com.dmanime.rules.model.AntiCrawlerConfig
import com.dmanime.rules.model.CaptchaDetectType
import com.dmanime.rules.model.RequestBodyTypes
import com.dmanime.rules.model.Road
import com.dmanime.rules.model.SearchItem
import com.dmanime.rules.net.HttpRequest
import java.net.URI
import java.net.URLDecoder
import org.w3c.dom.Node

/** XPath 模式：搜索页 → 条目 → 详情页 → 线路/剧集。 */
class XPathStrategy {

    fun prepareSearchRequest(rule: AnimeRule, keyword: String): HttpRequest {
        val url = rule.searchUrl.replace("@keyword", Template.encodeComponent(keyword))
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrEmpty() || uri.host.isNullOrEmpty()) {
            throw RuleFormatException("搜索 URL 无效: $url")
        }
        if (!rule.usePost) return HttpRequest(method = "GET", url = url)

        // POST 搜索：Kazumi 把 query 串当表单体提交，URL 只保留路径。
        val form = parseQuery(uri.rawQuery)
        val postUrl = buildString {
            append(uri.scheme).append("://").append(uri.rawAuthority).append(uri.rawPath ?: "")
        }
        return HttpRequest(
            method = "POST",
            url = postUrl,
            form = form,
            bodyType = RequestBodyTypes.FORM,
        )
    }

    fun prepareChapterRequest(rule: AnimeRule, source: String): HttpRequest {
        val url = EpisodeUrl.normalize(rule.baseUrl, source)
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrEmpty() || uri.host.isNullOrEmpty()) {
            throw RuleFormatException("章节 URL 无效: $url")
        }
        return HttpRequest(method = "GET", url = url)
    }

    fun parseSearch(raw: String, rule: AnimeRule): Pair<List<SearchItem>, List<String>> {
        val document = HtmlXPath.parse(raw)
        val nodes = HtmlXPath.select(document, rule.searchList)
        // 名称可能来自与条目平行的另一组节点（CSS1 的 indexed 格式）。
        val namesByIndex = if (rule.searchNameSelector.isBlank()) {
            emptyList()
        } else {
            HtmlXPath.select(document, rule.searchNameSelector)
        }
        val items = ArrayList<SearchItem>(nodes.size)
        val diagnostics = ArrayList<String>()

        for ((index, node) in nodes.withIndex()) {
            try {
                val alignedName = namesByIndex.getOrNull(index)?.let { HtmlXPath.text(it) }
                val nameNode = if (rule.searchName.isBlank()) null
                else HtmlXPath.select(node, rule.searchName).firstOrNull()
                val resultNode = HtmlXPath.select(node, rule.searchResult).firstOrNull()
                val name = alignedName?.takeIf { it.isNotEmpty() }
                    ?: nameNode?.let { HtmlXPath.text(it) }.orEmpty()
                val source = resultNode?.let { HtmlXPath.href(it) }.orEmpty()
                if (name.isEmpty() || source.isEmpty()) {
                    diagnostics += "搜索节点 $index 缺少名称或来源，已跳过"
                    continue
                }
                items += SearchItem(name = name, src = source)
            } catch (e: RuleFormatException) {
                throw e
            } catch (e: Exception) {
                diagnostics += "搜索节点 $index 解析失败: ${e.message}"
            }
        }
        return items to diagnostics
    }

    fun parseChapters(raw: String, rule: AnimeRule): Pair<List<Road>, List<String>> {
        val document = HtmlXPath.parse(raw)
        // chapterRoads 为空表示「整页只有一条线路」（CSS1 的 no-channel 格式）。
        val roadNodes: List<Node> = if (rule.chapterRoads.isBlank()) {
            listOf(document)
        } else {
            HtmlXPath.select(document, rule.chapterRoads)
        }
        val roadNames = if (rule.roadNameSelector.isBlank()) {
            emptyList()
        } else {
            HtmlXPath.select(document, rule.roadNameSelector)
        }
        val roads = ArrayList<Road>(roadNodes.size)
        val diagnostics = ArrayList<String>()

        for ((roadIndex, roadNode) in roadNodes.withIndex()) {
            try {
                val episodeNodes = HtmlXPath.select(roadNode, rule.chapterResult)
                val urls = ArrayList<String>(episodeNodes.size)
                val names = ArrayList<String>(episodeNodes.size)

                for ((episodeIndex, episode) in episodeNodes.withIndex()) {
                    try {
                        val source = HtmlXPath.href(episode)
                        if (source.isEmpty()) {
                            diagnostics += "线路 $roadIndex 的剧集节点 $episodeIndex 缺少 URL，已跳过"
                            continue
                        }
                        val name = HtmlXPath.text(episode).replace(Regex("\\s+"), "")
                        urls += EpisodeUrl.normalize(rule.baseUrl, source)
                        names += name.ifEmpty { "第${episodeIndex + 1}集" }
                    } catch (e: Exception) {
                        diagnostics += "线路 $roadIndex 的剧集节点 $episodeIndex 解析失败: ${e.message}"
                    }
                }

                if (urls.isEmpty()) {
                    diagnostics += "线路 $roadIndex 没有有效剧集，已跳过"
                    continue
                }
                // XPath 规则没有"线路名"字段，与 Kazumi 一致用序号占位；
                // x-roadNameSelector 命中时用真实线路名。
                val configuredName = roadNames.getOrNull(roadIndex)
                    ?.let { HtmlXPath.text(it).trim() }
                    .orEmpty()
                roads += Road(
                    name = configuredName.ifEmpty { "播放线路${roads.size + 1}" },
                    urls = urls,
                    names = names,
                )
            } catch (e: RuleFormatException) {
                throw e
            } catch (e: Exception) {
                diagnostics += "线路节点 $roadIndex 解析失败: ${e.message}"
            }
        }
        return roads to diagnostics
    }

    /** 判断响应是否为验证页；只有 enabled 时才检查。 */
    fun detectsCaptcha(raw: String, config: AntiCrawlerConfig): Boolean {
        if (!config.enabled) return false

        val detectValue = config.captchaDetectValue.trim()
        if (detectValue.isNotEmpty()) {
            return when (config.captchaDetectType) {
                CaptchaDetectType.TEXT -> raw.contains(detectValue)
                CaptchaDetectType.REGEX -> runCatching {
                    Regex(detectValue, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(raw)
                }.getOrDefault(false)
                else -> runCatching {
                    HtmlXPath.select(HtmlXPath.parse(raw), detectValue).isNotEmpty()
                }.getOrDefault(false)
            }
        }

        val document by lazy { HtmlXPath.parse(raw) }
        for (selector in listOf(config.captchaImage, config.captchaButton)) {
            if (selector.isBlank()) continue
            val found = runCatching { HtmlXPath.select(document, selector).isNotEmpty() }.getOrDefault(false)
            if (found) return true
        }
        return false
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return rawQuery.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val idx = pair.indexOf('=')
                val key = if (idx < 0) pair else pair.substring(0, idx)
                val value = if (idx < 0) "" else pair.substring(idx + 1)
                decode(key) to decode(value)
            }
    }

    private fun decode(text: String): String =
        runCatching { URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
}
