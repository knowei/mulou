package com.dmanime.danmaku

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * B 站弹幕 XML 的解析。
 *
 * `x/v1/dm/list.so?oid={cid}` 返回的是 **raw deflate**（无 zlib 头）压缩的 XML，
 * 结构是 `<i><d p="时间,模式,字号,颜色,时间戳,池,用户,行号">正文</d>...</i>`。
 */
object BiliDanmaku {

    private val ENTRY = Regex(
        "<d p=\"([^\"]*)\"[^>]*>(.*?)</d>",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    /** raw deflate → UTF-8 文本；解压失败就当作已经是明文。 */
    fun decode(body: ByteArray): String {
        if (body.isEmpty()) return ""
        runCatching {
            val inflater = Inflater(true)
            try {
                inflater.setInput(body)
                val out = ByteArrayOutputStream(body.size * 4)
                val buffer = ByteArray(16 * 1024)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        out.write(buffer, 0, n)
                    }
                }
                return out.toString("UTF-8")
            } finally {
                inflater.end()
            }
        }
        return String(body, Charsets.UTF_8)
    }

    fun parse(xml: String): List<Danmaku> {
        val result = ArrayList<Danmaku>(512)
        for (match in ENTRY.findAll(xml)) {
            val attrs = match.groupValues[1].split(',')
            if (attrs.size < 4) continue
            val seconds = attrs[0].toDoubleOrNull() ?: continue
            val rawMode = attrs[1].toIntOrNull() ?: continue
            val sizePx = attrs[2].toIntOrNull() ?: Danmaku.DEFAULT_SIZE_PX
            val color = attrs[3].toIntOrNull() ?: 0xFFFFFF

            val mode = when (rawMode) {
                1, 2, 3, 6 -> Danmaku.Mode.Scroll
                4 -> Danmaku.Mode.Bottom
                5 -> Danmaku.Mode.Top
                else -> continue
            }

            val text = unescape(match.groupValues[2]).trim()
            if (text.isEmpty() || text.length > TEXT_LIMIT) continue

            result += Danmaku(
                timeMs = (seconds * 1000).toLong(),
                mode = mode,
                color = 0xFF000000.toInt() or (color and 0xFFFFFF),
                text = text,
                sizePx = sizePx.coerceIn(12, 48),
            )
        }
        return merge(result)
    }

    /**
     * 按时间排序并去重。
     * 同一个 cid 的不同分段（长视频按 6 分钟分片）可能带回重复条目，
     * 视频本身也可能被不同分P 关联到同一弹幕库。
     */
    private fun merge(items: List<Danmaku>): List<Danmaku> {
        val sorted = items.sortedBy { it.timeMs }
        val seen = HashSet<String>(sorted.size * 2)
        val out = ArrayList<Danmaku>(sorted.size)
        for (item in sorted) {
            if (seen.add("${item.timeMs}|${item.text}")) out += item
        }
        return out
    }

    private fun unescape(text: String): String {
        if (text.indexOf('&') < 0) return text
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    private const val TEXT_LIMIT = 120
}
