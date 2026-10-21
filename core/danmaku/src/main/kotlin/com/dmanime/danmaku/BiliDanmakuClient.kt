package com.dmanime.danmaku

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * B 站弹幕数据源。
 *
 * 三件事：按标题找番剧（season）→ 拿这部剧每集的 cid → 用 cid 拉弹幕。
 * 全程不需要登录、不需要会员、不需要 Cookie——番剧的弹幕库是公开的。
 *
 * 需要注意：`pgc/view/web/season` 给的是 B 站上架版本，集数/时长可能和你实际播放的
 * 第三方片源不一致，所以只用 cid 取弹幕，时间轴按秒对齐，偏移交给用户微调。
 */
class BiliDanmakuClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val ua: String = DEFAULT_UA,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    private var cachedMixinKey: String? = null
    private var cachedMixinAt: Long = 0

    // ---------------- 搜索 ----------------

    fun search(keyword: String): List<DanmakuSeason> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return emptyList()
        val key = mixinKey() ?: return emptyList()
        val params = BiliWbi.sign(
            mapOf(
                "search_type" to "media_bangumi",
                "keyword" to trimmed,
                "page" to "1",
            ),
            nowSeconds(),
            key,
        )
        val url = "https://api.bilibili.com/x/web-interface/wbi/search/type?" +
            params.entries.joinToString("&") { (k, v) -> "$k=${encodeComponent(v)}" }
        val data = getJson(url)["data"]?.jsonObject ?: return emptyList()
        val result = data["result"] as? JsonArray ?: return emptyList()

        return result.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val seasonId = obj["season_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val title = stripTags(obj.string("title"))
            if (title.isBlank()) return@mapNotNull null
            DanmakuSeason(
                seasonId = seasonId,
                title = title,
                originalTitle = stripTags(obj.string("org_title")),
                episodeCount = obj["ep_size"]?.jsonPrimitive?.intOrNull ?: 0,
                score = obj["media_score"]?.jsonObject?.get("score")
                    ?.jsonPrimitive?.doubleOrNull ?: 0.0,
                cover = obj.string("cover"),
                typeName = obj.string("season_type_name"),
                indexShow = stripTags(obj.string("index_show")),
            )
        }
    }

    // ---------------- 分集 ----------------

    fun episodes(seasonId: Long): List<DanmakuEpisode> {
        val url = "https://api.bilibili.com/pgc/view/web/season?season_id=$seasonId"
        val result = getJson(url)["result"]?.jsonObject ?: return emptyList()
        val list = result["episodes"] as? JsonArray ?: return emptyList()

        var order = 0
        return list.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val cid = obj["cid"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if (cid <= 0) return@mapNotNull null
            // section_type != 0 是 PV / 花絮 / 预告，不参与正片集数计数
            val isMain = (obj["section_type"]?.jsonPrimitive?.intOrNull ?: 0) == 0
            val rawTitle = obj.string("title").trim()
            val parsed = rawTitle.toIntOrNull()
            if (isMain) order++
            DanmakuEpisode(
                index = parsed ?: if (isMain) order else order,
                title = rawTitle,
                longTitle = stripTags(obj.string("long_title")),
                cid = cid,
                durationMs = (obj["duration"]?.jsonPrimitive?.longOrNull ?: 0L) * 1,
            )
        }
    }

    // ---------------- UGC（用户上传） ----------------

    /**
     * 在 UGC 区按标题搜视频。
     *
     * 番剧库没有的片子（未引进、国漫、冷门番）在 UGC 区常有搬运：UP 主把一个合集
     * 切成若干分 P，每个分 P 就是一集，各自带弹幕库。这条路不需要任何凭证。
     */
    fun searchUgc(keyword: String): List<UgcVideo> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return emptyList()
        val key = mixinKey() ?: return emptyList()
        val params = BiliWbi.sign(
            mapOf(
                "search_type" to "video",
                "keyword" to trimmed,
                "page" to "1",
            ),
            nowSeconds(),
            key,
        )
        val url = "https://api.bilibili.com/x/web-interface/wbi/search/type?" +
            params.entries.joinToString("&") { (k, v) -> "$k=${encodeComponent(v)}" }
        val data = getJson(url)["data"]?.jsonObject ?: return emptyList()
        val result = data["result"] as? JsonArray ?: return emptyList()

        return result.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val bvid = obj.string("bvid")
            if (bvid.isBlank()) return@mapNotNull null
            UgcVideo(
                bvid = bvid,
                title = stripTags(obj.string("title")),
                play = obj["play"]?.jsonPrimitive?.intOrNull ?: 0,
                durationSeconds = parseDuration(obj.string("duration")),
            )
        }
    }

    /** 取一个视频的分 P 列表；每个 P 当成一集，它的 cid 就是这一集的弹幕库。 */
    fun pages(bvid: String): List<DanmakuEpisode> {
        if (bvid.isBlank()) return emptyList()
        val data = getJson("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")["data"]
            ?.jsonObject ?: return emptyList()
        val title = stripTags(data.string("title"))
        val pages = data["pages"] as? JsonArray ?: return emptyList()

        return pages.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val cid = obj["cid"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            if (cid <= 0) return@mapNotNull null
            val page = obj["page"]?.jsonPrimitive?.intOrNull ?: 0
            DanmakuEpisode(
                index = page,
                title = obj.string("part").ifBlank { "P" + page },
                longTitle = title,
                cid = cid,
                // 分 P 的时长单位是秒，统一换成毫秒，跟番剧库那条路保持一致
                durationMs = (obj["duration"]?.jsonPrimitive?.longOrNull ?: 0L) * 1000,
            )
        }
    }

    // ---------------- 弹幕 ----------------

    fun comments(cid: Long): List<Danmaku> {
        if (cid <= 0) return emptyList()
        val body = getBytes("https://api.bilibili.com/x/v1/dm/list.so?oid=$cid")
        val xml = BiliDanmaku.decode(body)
        // 分片接口对大文件更稳，list.so 偶尔只给前 6000 条，先够用
        return BiliDanmaku.parse(xml)
    }

    // ---------------- wbi ----------------

    private fun mixinKey(): String? {
        val now = nowSeconds()
        cachedMixinKey?.let { if (now - cachedMixinAt < MIXIN_TTL_SECONDS) return it }
        val nav = runCatching { getJson("https://api.bilibili.com/x/web-interface/nav") }.getOrNull()
            ?: return cachedMixinKey
        val wbi = nav["data"]?.jsonObject?.get("wbi_img")?.jsonObject ?: return cachedMixinKey
        val img = wbi.string("img_url").substringAfterLast('/').substringBefore('.')
        val sub = wbi.string("sub_url").substringAfterLast('/').substringBefore('.')
        if (img.isBlank() || sub.isBlank()) return cachedMixinKey
        val key = BiliWbi.mixinKey(img, sub)
        cachedMixinKey = key
        cachedMixinAt = now
        return key
    }

    // ---------------- HTTP ----------------

    private fun getJson(url: String): JsonObject {
        val text = String(getBytes(url), Charsets.UTF_8)
        return Json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun getBytes(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            // 不压缩：弹幕接口本身已经是 deflate，再叠加 gzip 反而要解两层
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            } ?: return ByteArray(0)
            return stream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.string(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.content ?: "" }.getOrDefault("")

    private companion object {
        const val MIXIN_TTL_SECONDS = 3600L
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        val TAG = Regex("<[^>]+>")

        fun stripTags(text: String): String = text.replace(TAG, "").trim()

        fun encodeComponent(text: String): String =
            java.net.URLEncoder.encode(text, "UTF-8")

        /** 搜索接口的时长是 "560:11" / "1:02:03" 这种字符串，不是秒数。 */
        fun parseDuration(raw: String): Int {
            val parts = raw.trim().split(':').mapNotNull { it.toIntOrNull() }
            return when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                1 -> parts[0]
                else -> 0
            }
        }
    }
}
