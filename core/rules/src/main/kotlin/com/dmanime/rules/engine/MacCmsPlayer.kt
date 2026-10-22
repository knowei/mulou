package com.dmanime.rules.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * MacCMS 系站点把播放地址明文塞在播放页的 `player_aaaa` 里，前端只是拿它初始化播放器。
 *
 * 这类站通常还挂着广告脚本，会在主框架里整页跳转（实测叽哔动漫的播放页跳到广告
 * 落地页，WebView 嗅探从头到尾等不到媒体地址）。能直接从 HTML 读出来的就不必真的
 * 让页面放一遍：一次 HTTP 请求就够，还省掉一个 WebView。
 *
 * 解析不出来一律返回 null，调用方照常规走嗅探。
 */
object MacCmsPlayer {

    /** `var player_aaaa={...};` 或 `var player_aaaa={...}</script>`，JSON 里可能有嵌套对象。 */
    private val PLAYER_JSON = Regex(
        """player_aaaa\s*=\s*(\{.*?\})\s*(?:;|</script>)""",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val MEDIA = Regex("""\.(m3u8|mp4|m4v|mpd)(\?|#|$)""", RegexOption.IGNORE_CASE)

    /**
     * 从播放页 HTML 取媒体地址。
     *
     * @param pageUrl 播放页地址，用于补全相对路径（少数站写的是相对地址）。
     */
    fun embeddedMediaUrl(html: String, pageUrl: String = ""): String? {
        val raw = PLAYER_JSON.find(html)?.groupValues?.getOrNull(1) ?: return null
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null

        // encrypt=1 时 url 是密文，需要站点自己的解密接口，交给嗅探
        val encrypt = runCatching { obj["encrypt"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }
            .getOrDefault(0)
        if (encrypt != 0) return null

        val declared = runCatching { obj["url"]?.jsonPrimitive?.content }.getOrNull().orEmpty().trim()
        if (declared.isEmpty()) return null

        val url = absolute(pageUrl, declared) ?: return null
        if (!url.startsWith("http", ignoreCase = true)) return null
        // 只认媒体地址，避免把播放器壳页（player.html?url=...）当成流
        val path = url.substringBefore('?').substringBefore('#')
        if (!MEDIA.containsMatchIn(path)) return null
        return url
    }

    private fun absolute(pageUrl: String, raw: String): String? {
        if (raw.startsWith("http", ignoreCase = true)) return raw
        if (pageUrl.isBlank()) return null
        return runCatching { URI(pageUrl).resolve(raw).toString() }.getOrNull()
    }
}
