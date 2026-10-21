package com.dmanime.app

import android.content.Context
import com.dmanime.catalog.BangumiClient
import com.dmanime.catalog.CatalogSubject
import com.dmanime.catalog.CalendarDay
import com.dmanime.catalog.SearchResult
import com.dmanime.catalog.SubjectCharacter
import com.dmanime.catalog.SubjectDetail
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
    }
}
