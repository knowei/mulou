package com.dmanime.rules.engine

import com.dmanime.rules.error.RuleFormatException
import com.dmanime.rules.model.ApiChapterConfig
import com.dmanime.rules.model.ApiRequestConfig
import com.dmanime.rules.model.ApiSearchConfig
import com.dmanime.rules.model.RequestBodyTypes
import com.dmanime.rules.model.Road
import com.dmanime.rules.model.SearchItem
import com.dmanime.rules.net.HttpRequest
import com.dmanime.rules.net.appendQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/**
 * API 模式：直接请求 JSON 接口，用受限 JSONPath 取值。
 *
 * 语义与 Kazumi 的 `ApiRuleStrategy` 对齐，包括：
 * - 模板变量 `@keyword` / `@source` / `@roadIndex` / `@episodeIndex` / 响应变量；
 * - `sourceTemplate` 用朴素替换（`/GV@source/` 可用）；
 * - nested 与 delimited 两种章节格式；
 * - 线路/剧集缺失时的诊断信息而不是整体失败。
 */
class ApiStrategy {

    fun prepareSearchRequest(config: ApiSearchConfig, variables: Map<String, Any?>): HttpRequest =
        prepareRequest(config.request, variables)

    fun prepareChapterRequest(config: ApiChapterConfig, variables: Map<String, Any?>): HttpRequest =
        prepareRequest(config.request, variables)

    private fun prepareRequest(config: ApiRequestConfig, variables: Map<String, Any?>): HttpRequest {
        val method = config.method.uppercase()
        if (method != "GET" && method != "POST") {
            throw RuleFormatException("仅支持 GET/POST，当前为 $method")
        }
        if (config.url.isBlank()) throw RuleFormatException("API 请求 URL 不能为空")

        val url = Template.render(config.url.trim(), variables, encode = true)
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrEmpty() || uri.host.isNullOrEmpty()) {
            throw RuleFormatException("API 请求 URL 无效: $url")
        }

        val headers = config.headers.mapValues { (_, value) -> renderScalar(value, variables) }
        val query = config.query.mapValues { (_, value) -> renderScalar(value, variables) }

        val hasBody = method == "POST" && config.bodyType != RequestBodyTypes.NONE
        var form: Map<String, String> = emptyMap()
        var jsonBody: JsonElement? = null
        if (hasBody) {
            val rendered = config.body?.let { Template.renderValue(it, variables) } ?: JsonNull
            when (config.bodyType) {
                RequestBodyTypes.JSON -> jsonBody = rendered
                RequestBodyTypes.FORM -> {
                    val obj = rendered as? JsonObject
                        ?: throw RuleFormatException("表单体必须是 JSON 对象")
                    form = obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }
                }
            }
        }

        return HttpRequest(
            method = method,
            url = url,
            headers = headers,
            query = query,
            form = form,
            bodyType = config.bodyType,
            jsonBody = jsonBody,
        )
    }

    fun validateSearchConfig(config: ApiSearchConfig) {
        RestrictedJsonPath.validate(config.listPath)
        RestrictedJsonPath.validate(config.namePath)
        RestrictedJsonPath.validate(config.sourcePath)
        if (config.sourceTemplate.isNotEmpty() && !config.sourceTemplate.contains("@source")) {
            throw RuleFormatException("详情页地址模板必须引用 @source")
        }
    }

    fun validateChapterConfig(config: ApiChapterConfig) {
        config.variables.values.forEach { RestrictedJsonPath.validate(it) }
        if (config.isDelimited) {
            RestrictedJsonPath.validate(config.roadNamesPath)
            RestrictedJsonPath.validate(config.roadEpisodesPath)
            if (config.roadSeparator.isEmpty() || config.episodeSeparator.isEmpty() || config.fieldSeparator.isEmpty()) {
                throw RuleFormatException("章节分隔符不能为空")
            }
            return
        }

        if (config.roadsPath.isNotBlank()) RestrictedJsonPath.validate(config.roadsPath)
        if (config.roadNamePath.isNotBlank()) RestrictedJsonPath.validate(config.roadNamePath)
        RestrictedJsonPath.validate(config.episodesPath)
        RestrictedJsonPath.validate(config.episodeNamePath)

        if (config.episodeUrlPath.isNotBlank()) {
            RestrictedJsonPath.validate(config.episodeUrlPath)
        } else if (config.episodePage == null) {
            throw RuleFormatException("必须配置播放入口地址路径或播放页地址模板")
        }
        if (config.episodePage != null && config.episodePage.url.isBlank()) {
            throw RuleFormatException("播放页地址模板不能为空")
        }
    }

    fun parseSearch(raw: String, config: ApiSearchConfig): Pair<List<SearchItem>, List<String>> {
        validateSearchConfig(config)
        val document = decodeResponse(raw)
        val nodes = RestrictedJsonPath.read(document, config.listPath)
        val items = ArrayList<SearchItem>(nodes.size)
        val diagnostics = ArrayList<String>()

        for ((index, node) in nodes.withIndex()) {
            try {
                val name = stringValue(RestrictedJsonPath.readFirst(node, config.namePath))
                val source = stringValue(RestrictedJsonPath.readFirst(node, config.sourcePath))
                if (name.isEmpty() || source.isEmpty()) {
                    diagnostics += "搜索节点 $index 缺少名称或来源，已跳过"
                    continue
                }
                items += SearchItem(
                    name = name,
                    src = Template.renderSourceTemplate(config.sourceTemplate, source),
                )
            } catch (e: RuleFormatException) {
                throw e
            } catch (e: Exception) {
                diagnostics += "搜索节点 $index 解析失败: ${e.message}"
            }
        }
        return items to diagnostics
    }

    fun parseChapters(
        raw: String,
        config: ApiChapterConfig,
        source: String,
        baseUrl: String,
    ): Pair<List<Road>, List<String>> {
        validateChapterConfig(config)
        val document = decodeResponse(raw)

        val rootVariables = LinkedHashMap<String, Any?>()
        rootVariables["source"] = source
        for ((name, path) in config.variables) {
            val value = RestrictedJsonPath.readFirst(document, path)
                ?: throw RuleFormatException("章节响应变量 $name 未匹配到值: $path")
            rootVariables[name] = jsonToKotlin(value)
        }

        val diagnostics = ArrayList<String>()
        val roads = if (config.isDelimited) {
            parseDelimited(document, config, rootVariables, baseUrl, diagnostics)
        } else {
            parseNested(document, config, rootVariables, baseUrl, diagnostics)
        }
        return roads to diagnostics
    }

    private fun parseNested(
        document: JsonElement,
        config: ApiChapterConfig,
        rootVariables: Map<String, Any?>,
        baseUrl: String,
        diagnostics: MutableList<String>,
    ): List<Road> {
        val hasRoads = config.roadsPath.isNotBlank()
        val roadNodes = if (hasRoads) RestrictedJsonPath.read(document, config.roadsPath) else listOf(document)
        val roads = ArrayList<Road>()

        for ((roadIndex, roadNode) in roadNodes.withIndex()) {
            try {
                val roadName = if (hasRoads && config.roadNamePath.isNotBlank()) {
                    stringValue(RestrictedJsonPath.readFirst(roadNode, config.roadNamePath))
                } else ""

                val episodeNodes = RestrictedJsonPath.read(roadNode, config.episodesPath)
                val urls = ArrayList<String>(episodeNodes.size)
                val names = ArrayList<String>(episodeNodes.size)

                for ((episodeIndex, episodeNode) in episodeNodes.withIndex()) {
                    try {
                        val episodeName = stringValue(
                            RestrictedJsonPath.readFirst(episodeNode, config.episodeNamePath)
                        )
                        val rawUrl = if (config.episodeUrlPath.isBlank()) "" else stringValue(
                            RestrictedJsonPath.readFirst(episodeNode, config.episodeUrlPath)
                        )
                        val pageUrl = resolveEpisodeUrl(
                            config, rootVariables, rawUrl, roadIndex, episodeIndex, baseUrl
                        )
                        if (pageUrl.isEmpty()) {
                            diagnostics += "线路 $roadIndex 的剧集节点 $episodeIndex 缺少 URL，已跳过"
                            continue
                        }
                        urls += pageUrl
                        names += episodeName.ifEmpty { "第${episodeIndex + 1}集" }
                    } catch (e: RuleFormatException) {
                        throw e
                    } catch (e: Exception) {
                        diagnostics += "线路 $roadIndex 的剧集节点 $episodeIndex 解析失败: ${e.message}"
                    }
                }

                if (urls.isEmpty()) {
                    diagnostics += "线路节点 $roadIndex 没有有效剧集，已跳过"
                    continue
                }
                roads += Road(
                    name = roadName.ifEmpty { "播放线路${roads.size + 1}" },
                    urls = urls,
                    names = names,
                )
            } catch (e: RuleFormatException) {
                throw e
            } catch (e: Exception) {
                diagnostics += "线路节点 $roadIndex 解析失败: ${e.message}"
            }
        }
        return roads
    }

    private fun parseDelimited(
        document: JsonElement,
        config: ApiChapterConfig,
        rootVariables: Map<String, Any?>,
        baseUrl: String,
        diagnostics: MutableList<String>,
    ): List<Road> {
        val namesValue = stringValue(RestrictedJsonPath.readFirst(document, config.roadNamesPath))
        val episodesValue = stringValue(RestrictedJsonPath.readFirst(document, config.roadEpisodesPath))
        if (episodesValue.isEmpty()) return emptyList()

        val roadNames = namesValue.split(config.roadSeparator)
        val roadGroups = episodesValue.split(config.roadSeparator)
        val roads = ArrayList<Road>()

        for ((roadIndex, group) in roadGroups.withIndex()) {
            val urls = ArrayList<String>()
            val names = ArrayList<String>()
            val entries = group.split(config.episodeSeparator)

            for ((episodeIndex, entry) in entries.withIndex()) {
                val trimmed = entry.trim()
                if (trimmed.isEmpty()) continue
                val separatorIndex = trimmed.indexOf(config.fieldSeparator)
                if (separatorIndex < 0) {
                    diagnostics += "线路 $roadIndex 的剧集条目 $episodeIndex 缺少字段分隔符，已跳过"
                    continue
                }
                val name = trimmed.substring(0, separatorIndex).trim()
                val rawUrl = trimmed.substring(separatorIndex + config.fieldSeparator.length).trim()
                try {
                    val pageUrl = resolveEpisodeUrl(
                        config, rootVariables, rawUrl, roadIndex, episodeIndex, baseUrl
                    )
                    if (pageUrl.isEmpty()) {
                        diagnostics += "线路 $roadIndex 的剧集条目 $episodeIndex 缺少 URL，已跳过"
                        continue
                    }
                    urls += pageUrl
                    names += name.ifEmpty { "第${episodeIndex + 1}集" }
                } catch (e: RuleFormatException) {
                    throw e
                } catch (e: Exception) {
                    diagnostics += "线路 $roadIndex 的剧集条目 $episodeIndex 解析失败: ${e.message}"
                }
            }

            if (urls.isEmpty()) {
                diagnostics += "线路 $roadIndex 没有有效剧集，已跳过"
                continue
            }
            val configuredName = roadNames.getOrNull(roadIndex)?.trim().orEmpty()
            roads += Road(
                name = configuredName.ifEmpty { "播放线路${roads.size + 1}" },
                urls = urls,
                names = names,
            )
        }
        return roads
    }

    private fun resolveEpisodeUrl(
        config: ApiChapterConfig,
        rootVariables: Map<String, Any?>,
        rawUrl: String,
        roadIndex: Int,
        episodeIndex: Int,
        baseUrl: String,
    ): String {
        val page = config.episodePage ?: return EpisodeUrl.normalize(baseUrl, rawUrl)
        if (page.url.isBlank()) throw RuleFormatException("播放页地址模板不能为空")

        val variables = LinkedHashMap<String, Any?>(rootVariables)
        variables["episodeUrl"] = rawUrl
        variables["roadIndex"] = roadIndex
        variables["roadNumber"] = roadIndex + 1
        variables["episodeIndex"] = episodeIndex
        variables["episodeNumber"] = episodeIndex + 1

        val rendered = Template.render(page.url, variables, encode = true)
        val uri = runCatching { URI(rendered) }.getOrNull()
            ?: throw RuleFormatException("剧集页面 URL 无效: $rendered")

        val existing = parseRawQuery(uri.rawQuery)
        val renderedQuery = page.query.mapValues { (_, value) -> renderScalar(value, variables) }
        val merged = LinkedHashMap(existing).apply { putAll(renderedQuery) }

        val base = buildString {
            append(uri.scheme).append("://").append(uri.rawAuthority)
            append(uri.rawPath.orEmpty())
        }
        return EpisodeUrl.normalize(baseUrl, appendQuery(base, merged))
    }

    private fun decodeResponse(raw: String): JsonElement =
        try {
            Json.parseToJsonElement(raw)
        } catch (e: Exception) {
            throw RuleFormatException("API 响应不是有效 JSON: ${e.message}")
        }

    private fun renderScalar(value: JsonElement, variables: Map<String, Any?>): String =
        when (value) {
            is JsonPrimitive -> if (value.isString) Template.render(value.content, variables) else value.content
            else -> Template.render(value.toString(), variables)
        }

    private fun stringValue(value: JsonElement?): String = when {
        value == null || value is JsonNull -> ""
        value is JsonPrimitive -> value.content.trim()
        value is JsonArray -> value.toString()
        else -> value.toString()
    }

    private fun jsonToKotlin(value: JsonElement?): Any? = when {
        value == null || value is JsonNull -> null
        value is JsonPrimitive -> when {
            value.isString -> value.content
            value.content == "true" || value.content == "false" -> value.content.toBoolean()
            else -> value.content.toLongOrNull() ?: value.content.toDoubleOrNull() ?: value.content
        }
        else -> value
    }

    private fun parseRawQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return rawQuery.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) pair to "" else pair.substring(0, idx) to pair.substring(idx + 1)
            }
    }
}
