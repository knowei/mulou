package com.dmanime.danmaku

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

/** 弹弹play 搜到的一部作品。 */
data class DanDanPlayAnime(
    val animeId: Long,
    val animeTitle: String,
    val type: String,
    val typeDescription: String,
    val episodes: List<DanDanPlayEpisode>,
)

/** 弹弹play 的一个剧集 id，用它换弹幕。 */
data class DanDanPlayEpisode(
    val episodeId: Long,
    val episodeTitle: String,
)

/**
 * 弹弹play 的签名。
 *
 * 官方算法：`base64(sha256(AppId + Timestamp + Path + AppSecret))`。
 * `Path` 是**不含域名和 query 的路径且全小写**——写成 `/API/v2/...` 会直接 403，
 * 而返回体是空的，只有一个 `X-Error-Message` 头，很难看出是大小写的问题。
 */
object DanDanPlaySign {

    fun sign(appId: String, timestampSeconds: Long, path: String, secret: String): String {
        val raw = appId + timestampSeconds + path.lowercase() + secret
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }
}

/**
 * 弹弹play 弹幕源（需在 https://dev.dandanplay.com 申请 AppId）。
 *
 * 与 B 站两路相比，它的价值在「聚合」：`withRelated=true` 会把 A 站、巴哈等第三方站
 * 同一集的弹幕一起带回来，所以对没有 B 站版权的番（国漫、欧美动画）往往还有货。
 *
 * 免费用，但禁止商用、禁止批量抓取或建库，所以这里只在用户真的打开这个来源时才请求，
 * 并且和 B 站一样按集缓存到本地。
 */
class DanDanPlayClient(
    private val appId: String,
    private val secret: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val ua: String = DEFAULT_UA,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    val configured: Boolean get() = appId.isNotBlank() && secret.isNotBlank()

    // ---------------- 搜索 ----------------

    /** `/api/v2/search/episodes`：按番剧名 + 集号直接搜到剧集 id。 */
    fun searchEpisodes(anime: String, episode: Int): List<DanDanPlayAnime> {
        val name = anime.trim()
        if (name.isEmpty()) return emptyList()
        val path = "/api/v2/search/episodes"
        val query = "?anime=" + encodeComponent(name) + "&episode=" + episode
        val root = getJson(path, query)

        val animes = root["animes"] as? JsonArray ?: return emptyList()
        return animes.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["animeId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val title = obj.string("animeTitle")
            if (title.isBlank()) return@mapNotNull null
            val episodes = (obj["episodes"] as? JsonArray).orEmpty().mapNotNull { ep ->
                val eo = ep as? JsonObject ?: return@mapNotNull null
                val epId = eo["episodeId"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                DanDanPlayEpisode(epId, eo.string("episodeTitle"))
            }
            DanDanPlayAnime(
                animeId = id,
                animeTitle = title,
                type = obj.string("type"),
                typeDescription = obj.string("typeDescription"),
                episodes = episodes,
            )
        }
    }

    // ---------------- 弹幕 ----------------

    /**
     * `/api/v2/comment/{episodeId}`：取一集的弹幕。
     *
     * `withRelated=true` 会把第三方站的同集弹幕一起聚合回来——这也是它比 B 站多出来的那部分。
     */
    fun comments(episodeId: Long, withRelated: Boolean = true): List<Danmaku> {
        if (episodeId <= 0) return emptyList()
        val path = "/api/v2/comment/$episodeId"
        val query = if (withRelated) "?withRelated=true&chConvert=0" else "?chConvert=0"
        val root = getJson(path, query)

        val list = root["comments"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val text = obj.string("m")
            if (text.isBlank() || text.length > MAX_TEXT_LENGTH) return@mapNotNull null
            parseP(obj.string("p"), text)
        }.sortedBy { it.timeMs }
            .distinctBy { it.timeMs to it.text }
    }

    /** `p` 是 "时间,模式,颜色,用户ID"：第 4 段是用户 id，不是时间戳，别照抄 B 站的九段格式。 */
    private fun parseP(raw: String, text: String): Danmaku? {
        val parts = raw.split(',')
        val seconds = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
        val mode = when (parts.getOrNull(1)?.trim()?.toIntOrNull()) {
            4 -> Danmaku.Mode.Bottom
            5 -> Danmaku.Mode.Top
            1, 2, 3 -> Danmaku.Mode.Scroll
            else -> return null
        }
        val rgb = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0xFFFFFF
        return Danmaku(
            timeMs = (seconds * 1000).toLong(),
            mode = mode,
            color = 0xFF000000.toInt() or (rgb and 0xFFFFFF),
            text = text,
        )
    }

    // ---------------- HTTP ----------------

    private fun getJson(path: String, query: String): JsonObject {
        val ts = nowSeconds()
        val signature = DanDanPlaySign.sign(appId, ts, path, secret)
        val connection = (URL(BASE + path + query).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-AppId", appId)
            setRequestProperty("X-Timestamp", ts.toString())
            setRequestProperty("X-Signature", signature)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                // 403 的响应体是空的，原因只在头里：Invalid AppId / App not approved …
                val reason = connection.getHeaderField("X-Error-Message")
                    ?: connection.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }
                    ?: ""
                throw IllegalStateException(
                    when {
                        code == 403 && reason.isBlank() -> "弹弹play 拒绝访问（AppId 未生效或应用未通过审核）"
                        reason.contains("Invalid AppId", ignoreCase = true) ->
                            "弹弹play 未启用：服务端不认这个 AppId（应用大概还在审核）"
                        reason.contains("not approved", ignoreCase = true) ->
                            "弹弹play 未启用：应用还没有通过审核"
                        reason.isBlank() -> "弹弹play 返回 $code"
                        else -> "弹弹play 拒绝访问：$reason"
                    },
                )
            }
            val text = connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            return Json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.string(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.content ?: "" }.getOrDefault("")

    private fun encodeComponent(text: String): String =
        java.net.URLEncoder.encode(text, "UTF-8")

    private companion object {
        const val BASE = "https://api.dandanplay.net"
        const val MAX_TEXT_LENGTH = 120
        const val DEFAULT_UA = "Komo/1.0 (Android)"
    }
}
