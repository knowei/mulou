package com.dmanime.rules.css1

import com.dmanime.rules.error.RuleFormatException
import com.dmanime.rules.model.AnimeRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI

/**
 * Kazumi Hub / CSS1 规则包 → 本项目规则。
 *
 * CSS1 的结构是「一份 `css1.json` 里装 N 个 `web-selector` 源」，每个源用 CSS
 * 选择器描述三件事：搜索结果列表、线路块与剧集、播放页取流。前两件事可以完整
 * 翻译成 XPath；第三件事（`matchVideo`）在本项目里由 WebView 嗅探接管，不需要
 * 迁移，所以转换只处理 `searchConfig`。
 *
 * 三个格式的映射：
 * - `subjectFormatId = a`：列表节点本身就是 `<a>`，名称取节点文本、地址取 href；
 * - `subjectFormatId = indexed`：名称和链接是两组平行节点，用新增的
 *   `x-searchNameSelector` 按索引对齐；
 * - `channelFormatId = no-channel`：`chapterRoads` 留空，整页一条线路；
 * - `index-grouped`：线路块照搬，线路名走 `x-roadNameSelector`。
 *
 * 不迁移的字段会记进 [Outcome.notes]，不静默丢弃。
 */
object Css1Converter {

    data class Outcome(
        val rules: List<AnimeRule>,
        /** 整条规则无法转换的原因。 */
        val skipped: List<String>,
        /** 转换成功但有信息损失的说明。 */
        val notes: List<String>,
    )

    fun looksLikeCss1(root: JsonElement): Boolean {
        val obj = root as? JsonObject ?: return false
        return obj["exportedMediaSourceDataList"] is JsonObject
    }

    /** 文本入口：解析、判定格式、转换。JSON 不合法时抛 [RuleFormatException]。 */
    fun convert(text: String, reservedNames: Set<String> = emptySet()): Outcome {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw RuleFormatException("JSON 解析失败: ${e.message}")
        }
        if (!looksLikeCss1(root)) {
            throw RuleFormatException("不是 CSS1 规则包：缺少 exportedMediaSourceDataList")
        }
        return convert(root, reservedNames)
    }

    /**
     * @param reservedNames 已存在的规则名，用于去重（本项目以 name 作为规则身份）。
     */
    fun convert(root: JsonElement, reservedNames: Set<String> = emptySet()): Outcome {
        val sources = sourcesOf(root)
        val used = LinkedHashSet(reservedNames)
        val rules = ArrayList<AnimeRule>()
        val skipped = ArrayList<String>()
        val notes = ArrayList<String>()

        for ((index, element) in sources.withIndex()) {
            val item = element as? JsonObject
            if (item == null) {
                skipped += "第 ${index + 1} 条不是对象"
                continue
            }
            val factory = item.str("factoryId")
            if (factory != "web-selector") {
                skipped += "第 ${index + 1} 条 factoryId=$factory，不是 web-selector"
                continue
            }
            val args = item["arguments"] as? JsonObject
            if (args == null) {
                skipped += "第 ${index + 1} 条缺少 arguments"
                continue
            }
            val label = args.str("name").ifBlank { "第 ${index + 1} 条" }
            try {
                val result = convertOne(args, used)
                rules += result.rule
                notes += result.notes
            } catch (e: RuleFormatException) {
                skipped += "$label：${e.message}"
            }
        }
        return Outcome(rules, skipped, notes)
    }

    private fun sourcesOf(root: JsonElement): JsonArray {
        val obj = root as? JsonObject
            ?: throw RuleFormatException("不是 CSS1 规则包：根节点不是对象")
        val container = obj["exportedMediaSourceDataList"] as? JsonObject
        val array = container?.get("mediaSources") as? JsonArray
            ?: (obj["mediaSources"] as? JsonArray)
            ?: throw RuleFormatException("不是 CSS1 规则包：缺少 mediaSources")
        return array
    }

    private data class Converted(val rule: AnimeRule, val notes: List<String>)

    private fun convertOne(args: JsonObject, used: MutableSet<String>): Converted {
        val config = args["searchConfig"] as? JsonObject
            ?: throw RuleFormatException("缺少 searchConfig")

        val rawSearchUrl = config.str("searchUrl")
        if (rawSearchUrl.isBlank()) throw RuleFormatException("searchUrl 为空")
        if (!rawSearchUrl.contains("{keyword}")) {
            throw RuleFormatException("searchUrl 缺少 {keyword} 占位符")
        }

        val notes = ArrayList<String>()
        val name = uniqueName(args.str("name"), used)

        val search = mapSearch(config)
        val chapters = mapChapters(config)

        if (search.nameSelector.isNotEmpty()) {
            notes += "$name：indexed 格式，标题与链接是两组节点，依赖 x-searchNameSelector 对齐"
        }
        if (chapters.singleRoad) {
            notes += "$name：no-channel 格式，整页只有一条线路"
        }
        if (chapters.separateEpisodeLink) {
            notes += "$name：剧集名与链接是两组节点，已改用链接节点（名称取链接文本）"
        }

        val rule = AnimeRule(
            name = name,
            version = "1.0",
            baseUrl = baseUrlOf(config, rawSearchUrl),
            searchUrl = rawSearchUrl.replace("{keyword}", "@keyword"),
            searchList = search.list,
            searchName = search.name,
            searchResult = search.result,
            searchNameSelector = search.nameSelector,
            chapterRoads = chapters.roads,
            chapterResult = chapters.result,
            roadNameSelector = chapters.roadNames,
        )
        return Converted(rule, notes)
    }

    // ---------------------------------------------------------------- 搜索

    private data class SearchMapping(
        val list: String,
        val name: String,
        val result: String,
        val nameSelector: String = "",
    )

    private fun mapSearch(config: JsonObject): SearchMapping {
        val format = config.str("subjectFormatId").ifBlank { "a" }
        return when (format) {
            "a" -> {
                val css = (config["selectorSubjectFormatA"] as? JsonObject)
                    ?.str("selectLists").orEmpty()
                if (css.isBlank()) throw RuleFormatException("selectorSubjectFormatA.selectLists 为空")
                val anchor = endsWithAnchor(css)
                SearchMapping(
                    list = CssSelector.toXPath(css),
                    name = if (anchor) "." else ".//a",
                    result = if (anchor) "." else ".//a",
                )
            }

            "indexed" -> {
                val node = config["selectorSubjectFormatIndexed"] as? JsonObject
                    ?: throw RuleFormatException("缺少 selectorSubjectFormatIndexed")
                val links = node.str("selectLinks")
                if (links.isBlank()) throw RuleFormatException("selectLinks 为空")
                val names = node.str("selectNames")
                SearchMapping(
                    list = CssSelector.toXPath(links),
                    name = ".",
                    result = ".",
                    nameSelector = if (names.isBlank()) "" else CssSelector.toXPath(names),
                )
            }

            else -> throw RuleFormatException("不支持的 subjectFormatId=$format")
        }
    }

    /** 最后一个复合选择器是不是 `<a>`（决定名称/链接取节点自身还是内部链接）。 */
    private fun endsWithAnchor(css: String): Boolean {
        val last = css.trim()
            .split(Regex("""\s+|\s*>\s*"""))
            .lastOrNull()
            .orEmpty()
        if (!last.startsWith("a", ignoreCase = true)) return false
        if (last.length == 1) return true
        val next = last[1]
        return !(next.isLetterOrDigit() || next == '-' || next == '_')
    }

    // ---------------------------------------------------------------- 章节

    private data class ChapterMapping(
        val roads: String,
        val result: String,
        val roadNames: String,
        val singleRoad: Boolean,
        val separateEpisodeLink: Boolean,
    )

    private fun mapChapters(config: JsonObject): ChapterMapping {
        val format = config.str("channelFormatId").ifBlank { "index-grouped" }

        if (format == "no-channel") {
            val node = config["selectorChannelFormatNoChannel"] as? JsonObject
            val episodes = node.str("selectEpisodes").ifBlank { node.str("selectEpisodeLinks") }
            if (episodes.isBlank()) {
                throw RuleFormatException("selectorChannelFormatNoChannel.selectEpisodes 为空")
            }
            return ChapterMapping(
                roads = "",
                result = CssSelector.toXPath(episodes, relative = true),
                roadNames = "",
                singleRoad = true,
                separateEpisodeLink = false,
            )
        }
        if (format != "index-grouped") throw RuleFormatException("不支持的 channelFormatId=$format")

        val node = config["selectorChannelFormatFlattened"] as? JsonObject
            ?: throw RuleFormatException("缺少 selectorChannelFormatFlattened")
        val listCss = node.str("selectEpisodeLists")
        if (listCss.isBlank()) throw RuleFormatException("selectEpisodeLists 为空")

        val episodeCss = node.str("selectEpisodesFromList").ifBlank { "a" }
        val linkCss = node.str("selectEpisodeLinksFromList")
        val result = if (linkCss.isNotBlank()) {
            // 剧集名和链接是两组平行节点（如 `a span` 与 `a`），取链接那组，
            // 名称从链接文本里拿。
            CssSelector.toXPath(relativeTo(linkCss, listCss), relative = true)
        } else {
            CssSelector.toXPath(episodeCss, relative = true)
        }

        val namesCss = node.str("selectChannelNames")
        return ChapterMapping(
            roads = CssSelector.toXPath(listCss),
            result = result,
            roadNames = if (namesCss.isBlank()) "" else CssSelector.toXPath(namesCss),
            singleRoad = false,
            separateEpisodeLink = linkCss.isNotBlank(),
        )
    }

    /**
     * `selectEpisodeLinksFromList` 经常以线路容器自己开头
     * （`#playlist-scroll > div > a`），而它要在线路节点内部求值。
     * 前缀能对上就剥掉，让表达式变成相对路径。
     */
    private fun relativeTo(linkCss: String, containerCss: String): String {
        val link = linkCss.trim()
        val container = containerCss.trim()
        if (!link.startsWith(container)) return link
        val rest = link.substring(container.length).trim()
        if (rest.isEmpty()) return link
        if (rest.startsWith(">")) return rest.substring(1).trim()
        return rest
    }

    // ---------------------------------------------------------------- 其它

    private fun baseUrlOf(config: JsonObject, searchUrl: String): String {
        val raw = config.str("rawBaseUrl")
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return if (raw.endsWith("/")) raw else "$raw/"
        }
        // searchUrl 里的 {keyword} 不是合法 URI 字符，先换成占位再取 origin。
        val uri = runCatching { URI(searchUrl.replace("{keyword}", "keyword")) }.getOrNull()
        val scheme = uri?.scheme
        val authority = uri?.rawAuthority
        if (scheme.isNullOrEmpty() || authority.isNullOrEmpty()) {
            throw RuleFormatException("无法从 searchUrl 推导站点域名: $searchUrl")
        }
        return "$scheme://$authority/"
    }

    private fun uniqueName(raw: String, used: MutableSet<String>): String {
        val base = raw.trim().ifBlank { "未命名源" }
        var candidate = base
        var suffix = 2
        while (!used.add(candidate)) {
            candidate = "$base（$suffix）"
            suffix++
        }
        return candidate
    }

    private fun JsonObject?.str(key: String): String =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
}
