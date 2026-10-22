package com.dmanime.app

import android.content.Context
import com.dmanime.catalog.BangumiClient
import com.dmanime.catalog.CatalogSubject
import com.dmanime.catalog.CalendarDay
import com.dmanime.catalog.SearchResult
import com.dmanime.catalog.SubjectCharacter
import com.dmanime.catalog.SubjectDetail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 番剧索引仓库。
 *
 * 两件事：
 * 1. **缓存优先**：启动先读本地 JSON 立刻渲染，再后台刷新；断网/域名不可达也不白屏。
 * 2. **源可配置**：`api.bgm.tv` 在部分网络下会被 DNS 污染或不可达，
 *    允许改成可达的镜像/反代（例如 Cloudflare 映射的域名），也允许直接导入 JSON。
 */
class CatalogRepository(context: Context) {

    private val cacheFile = File(context.filesDir, "calendar.json")
    private val prefs = context.getSharedPreferences("catalog", Context.MODE_PRIVATE)
    private val parser = BangumiClient { "" }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE, BangumiClient.BASE)?.trimEnd('/') ?: BangumiClient.BASE
        set(value) {
            prefs.edit().putString(KEY_BASE, value.trim().trimEnd('/')).apply()
        }

    /** 封面前缀：空表示直连 lain.bgm.tv；填了就用它替换域名（例如 http://host/img）。 */
    var imageBase: String
        get() = prefs.getString(KEY_IMAGE, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_IMAGE, value.trim().trimEnd('/')).apply()
        }

    fun cached(): List<CalendarDay>? {
        if (!cacheFile.exists()) return null
        return runCatching { parser.parseCalendar(cacheFile.readText()) }.getOrNull()
    }

    fun cachedAt(): Long = if (cacheFile.exists()) cacheFile.lastModified() else 0L

    fun refresh(): List<CalendarDay> {
        val text = fetch(calendarUrl())
        val days = parseOrExplain(text)
        runCatching { cacheFile.writeText(text) }
        return days
    }

    /** 单条目详情（简介全文 / 标签 / 集数 / 评分人数），走同一个反代。 */
    fun subjectDetail(id: Int): SubjectDetail {
        val text = fetch("$baseUrl/v0/subjects/$id")
        return runCatching { parser.parseSubject(text) }.getOrElse {
            throw IllegalStateException("详情接口返回异常：${text.take(80)}")
        }
    }

    /** 角色 + 声优。反代对 /characters 会包一层 {value:[...]}，解析在 core 里兼容两种形状。 */
    fun characters(id: Int): List<SubjectCharacter> {
        val text = fetch("$baseUrl/v0/subjects/$id/characters")
        return runCatching { parser.parseCharacters(text) }.getOrDefault(emptyList())
    }

    /**
     * 实际集数 = `GET /v0/episodes` 的条数。
     *
     * Bangumi 的 `total_episodes` 是**计划**集数，和实际放送会不一致：
     * 「约定的梦幻岛 第二季」计划 12 集，因制作原因只播了 11 集
     * （`total_episodes=12`、`eps=11`、ep 列表 11 条），
     * 直接拿 12 去显示就会永远写着"更新至 11 集 / 共 12 集"。
     * 反过来说，还在播的新番 `eps` 常常滞后，那时 ep 列表也只有已播的那几条，
     * 所以**只在番已经放完**的时候才用它校正（判断在 AppViewModel.fixEpisodeCount）。
     */
    fun episodeCountOf(id: Int): Int {
        val text = fetch("$baseUrl/v0/episodes?subject_id=$id&type=0&limit=100")
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return 0
        val total = root["total"]?.let { element -> runCatching { element.jsonPrimitive.int }.getOrNull() } ?: 0
        val size = (root["data"] as? JsonArray)?.size ?: 0
        return if (total > 0) total else size
    }

    /**
     * 季度番剧表：按首播日期区间筛选整季（含已完结和未开播的），返回 (列表, 总数)。
     *
     * Bangumi 的 search 支持 air_date 区间过滤，关键字可以留空；
     * 但 sort 只支持 match/heat/rank/score，所以按日期排序得在客户端做。
     */
    fun searchSeason(start: String, end: String, offset: Int, limit: Int = 50): Pair<List<CatalogSubject>, Int> {
        val body = """{"keyword":"","filter":{"type":[2],"air_date":[">=$start","<$end"]}}"""
        val text = post("$baseUrl/v0/search/subjects?limit=$limit&offset=$offset", body)
        val response = runCatching { parser.parseSearch(text) }.getOrElse {
            throw IllegalStateException("季度接口返回异常：${text.take(80)}")
        }
        return response.data.map { it.toSubject() } to response.total
    }

    /**
     * 分类索引：按「地区 + 题材标签」筛整库，返回 (列表, 总数)。
     *
     * 服务端限制都是实测出来的，改动前先看一遍：
     * - `limit` 最大 20（给 50 也只回 20）；
     * - `offset` ≥ 1000 直接返回空数组，所以一个筛选条件最多只能看到 1000 条；
     * - `total` 也是封顶值（日本 1000、中国 1000、韩国 465 是真的），
     *   等于 1000 时说明是"至少 1000"，界面上不能写成准确总数；
     * - 多个 `tag` 之间是 **AND**，没有 OR，所以「日漫或韩漫」只能查两次拼起来。
     */
    fun filterSubjects(
        region: String,
        tags: List<String>,
        sort: String,
        offset: Int,
        limit: Int = 20,
    ): Pair<List<CatalogSubject>, Int> {
        val allTags = buildList {
            if (region.isNotBlank()) add(region)
            addAll(tags.filter { it.isNotBlank() })
        }
        val tagJson = allTags.joinToString(",") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
        val filter = if (tagJson.isEmpty()) """{"type":[2]}""" else """{"type":[2],"tag":[$tagJson]}"""
        val sortField = if (sort.isBlank()) "" else ""","sort":""" + "\"$sort\""
        val body = """{"keyword":"","filter":$filter$sortField}"""
        val pageSize = limit.coerceIn(1, 20)
        val pageOffset = offset.coerceIn(0, MAX_INDEX_OFFSET)
        val text = post("$baseUrl/v0/search/subjects?limit=$pageSize&offset=$pageOffset", body)
        val response = runCatching { parser.parseSearch(text) }.getOrElse {
            throw IllegalStateException("索引接口返回异常：${text.take(80)}")
        }
        return response.data.map { it.toSubject() } to response.total
    }

    /** 在线索引搜索：POST /v0/search/subjects（反代需要支持 POST，已实测可用）。 */
    fun searchSubjects(keyword: String): List<SearchResult> {
        val escaped = keyword.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = """{"keyword":"$escaped","filter":{"type":[2]}}"""
        val text = post("$baseUrl/v0/search/subjects?limit=20", body)
        return runCatching { parser.parseSearch(text).data }.getOrElse {
            throw IllegalStateException("搜索接口返回异常：${text.take(80)}")
        }
    }

    /** 连通性自检：只请求 + 解析、不写缓存。
     * 用来区分「域名解析不了 / 连接超时 / HTTP 4xx / 返回的不是 JSON」。
     */
    fun probe(): String {
        val started = System.currentTimeMillis()
        val text = fetch(calendarUrl())
        val days = parseOrExplain(text)
        val elapsed = System.currentTimeMillis() - started
        return "成功 · ${elapsed}ms · ${days.sumOf { it.items.size }} 部 · ${calendarUrl()}"
    }

    /** 直接导入一份日历 JSON（与原接口返回同结构），用于接口不可达时兜底。 */
    fun importFromText(text: String): List<CalendarDay> {
        val days = parseOrExplain(text)
        cacheFile.writeText(text)
        return days
    }

    private fun calendarUrl(): String =
        if (baseUrl.endsWith("/calendar")) baseUrl else "$baseUrl/calendar"

    /**
     * 把「返回了但不是日历」的情况翻译成人话。
     *
     * 典型场景：反代把路径重写成了 `/`，于是返回 `{"request":"/","code":404,...}`。
     * 这种错误如果只报"解析失败"，排查成本很高。
     */
    private fun parseOrExplain(text: String): List<CalendarDay> =
        runCatching { parser.parseCalendar(text) }.getOrElse {
            val body = text.trim()
            val hint = when {
                body.contains("\"code\":404") || body.contains("\"code\": 404") ->
                    "接口返回 404（请求路径可能没有被转发到函数）：${body.take(80)}"

                body.contains("\"error\"") -> "接口返回错误 JSON：${body.take(80)}"
                body.isEmpty() -> "接口返回空响应"
                else -> "返回内容不是日历 JSON：${body.take(60)}"
            }
            throw IllegalStateException(hint)
        }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", BangumiClient.USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun post(url: String, body: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", BangumiClient.USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val KEY_BASE = "base_url"
        const val KEY_IMAGE = "image_base"

        /** offset ≥ 1000 服务端返回空，翻到 980 就到头了。 */
        const val MAX_INDEX_OFFSET = 980
    }
}
