package com.dmanime.rules.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object RuleModes {
    const val XPATH = "xpath"
    const val API = "api"
}

object ChapterFormats {
    const val NESTED = "nested"
    const val DELIMITED = "delimited"
}

object RequestBodyTypes {
    const val NONE = "none"
    const val JSON = "json"
    const val FORM = "form"
}

/** 反反爬验证方式，取值与 Kazumi 的 CaptchaType 一致。 */
object CaptchaType {
    const val IMAGE = 1
    const val AUTO_CLICK = 2
    const val CUSTOM_JS = 3
}

/** 验证页检测方式，取值与 Kazumi 的 CaptchaDetectType 一致。 */
object CaptchaDetectType {
    const val XPATH = 1
    const val TEXT = 2
    const val REGEX = 3
}

/**
 * 一条番剧规则。
 *
 * 字段名与 Kazumi 的规则 schema 逐一对齐（包括历史拼写 `muliSources`），
 * 因此 KazumiRules 仓库里的规则可以直接解析。自定义能力放在 `x-` 前缀的
 * 扩展字段里，导出给 Kazumi 时剥掉即可。
 */
@Serializable
data class AnimeRule(
    /** 客户端能力版本，字符串，例如 "9"。 */
    val api: String = "1",
    val type: String = "anime",
    val name: String = "",
    val version: String = "",
    /** 历史拼写，勿改：multi → muli。 */
    @SerialName("muliSources") val multiSources: Boolean = true,
    val useWebview: Boolean = true,
    val useNativePlayer: Boolean = true,
    val usePost: Boolean = false,
    val useLegacyParser: Boolean = false,
    val adBlocker: Boolean = false,
    val userAgent: String = "",
    @SerialName("baseURL") val baseUrl: String = "",
    @SerialName("searchURL") val searchUrl: String = "",
    val searchList: String = "",
    val searchName: String = "",
    val searchResult: String = "",
    val chapterRoads: String = "",
    val chapterResult: String = "",
    val referer: String = "",
    /**
     * `x-` 扩展：搜索结果的名称来自**另一组平行节点**（CSS1 的 indexed 格式、
     * 以及「标题在左侧列表、链接在右侧列表」的站点）。
     *
     * 取值是文档级选择器，按索引与 [searchList] 的结果对齐；命中时优先于
     * [searchName]（后者仍在条目节点内查找）。为空表示不使用。
     */
    @SerialName("x-searchNameSelector") val searchNameSelector: String = "",
    /**
     * `x-` 扩展：线路名来自独立的一组节点（CSS1 的 `selectChannelNames`），
     * 按索引与 [chapterRoads] 对齐。为空时用「播放线路N」占位。
     */
    @SerialName("x-roadNameSelector") val roadNameSelector: String = "",
    val searchMode: String = RuleModes.XPATH,
    val chapterMode: String = RuleModes.XPATH,
    val searchApiConfig: ApiSearchConfig = ApiSearchConfig(),
    val chapterApiConfig: ApiChapterConfig = ApiChapterConfig(),
    val antiCrawlerConfig: AntiCrawlerConfig = AntiCrawlerConfig(),
    /** 规则仓库的标记位；解析时保留，运行时应跳过。 */
    val deprecated: Boolean = false,
) {
    val usesApiSearch: Boolean get() = searchMode == RuleModes.API
    val usesApiChapters: Boolean get() = chapterMode == RuleModes.API
}

@Serializable
data class ApiSearchConfig(
    val request: ApiRequestConfig = ApiRequestConfig(),
    val listPath: String = "\$.data[*]",
    val namePath: String = "\$.name",
    val sourcePath: String = "\$.url",
    /** 用 @source 拼出详情页地址；为空表示直接用原始来源值。 */
    val sourceTemplate: String = "",
)

@Serializable
data class ApiChapterConfig(
    val request: ApiRequestConfig = ApiRequestConfig(),
    val format: String = ChapterFormats.NESTED,
    // nested
    val roadsPath: String = "\$.data.roads[*]",
    val roadNamePath: String = "\$.name",
    val episodesPath: String = "\$.episodes[*]",
    val episodeNamePath: String = "\$.name",
    val episodeUrlPath: String = "\$.url",
    // delimited（苹果 CMS 的 $$$ / # / $ 三段切分）
    val roadNamesPath: String = "",
    val roadEpisodesPath: String = "",
    val roadSeparator: String = "$$$",
    val episodeSeparator: String = "#",
    val fieldSeparator: String = "$",
    /** 章节响应上的命名 JSONPath，渲染进 episodePage。 */
    val variables: Map<String, String> = emptyMap(),
    val episodePage: ApiEpisodePageConfig? = null,
) {
    val isDelimited: Boolean get() = format == ChapterFormats.DELIMITED
}

@Serializable
data class ApiEpisodePageConfig(
    val url: String = "",
    val query: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ApiRequestConfig(
    val method: String = "GET",
    val url: String = "",
    val headers: JsonObject = JsonObject(emptyMap()),
    val query: JsonObject = JsonObject(emptyMap()),
    val bodyType: String = RequestBodyTypes.NONE,
    val body: JsonElement? = null,
)

@Serializable
data class AntiCrawlerConfig(
    val enabled: Boolean = false,
    val captchaType: Int = CaptchaType.IMAGE,
    val captchaImage: String = "",
    val captchaInput: String = "",
    val captchaButton: String = "",
    val captchaDetectType: Int = CaptchaDetectType.XPATH,
    val captchaDetectValue: String = "",
    val captchaScript: String = "",
)

/**
 * 解析规则用的 Json 配置。
 *
 * - `ignoreUnknownKeys`：Kazumi 后续加字段不应让老客户端解析失败。
 * - `coerceInputValues`：把 `"referer": null` 这类脏数据落回默认值。
 */
val RuleJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
    prettyPrint = false
}

fun parseRule(text: String): AnimeRule = RuleJson.decodeFromString(text)

fun encodeRule(rule: AnimeRule): String = RuleJson.encodeToString(AnimeRule.serializer(), rule)
