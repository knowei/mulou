package com.dmanime.sniff

import java.net.URI

data class Variant(
    val url: String,
    val bandwidth: Int,
    val resolution: String?,
)

sealed interface M3u8Result {
    data object NotPlaylist : M3u8Result
    data class Master(val variants: List<Variant>) : M3u8Result
    data class Media(val encrypted: Boolean) : M3u8Result
}

/**
 * 极简 m3u8 解析：只做两件对播放有用的事——
 * 判断是 master 还是 media，以及（master 时）选出码率最高的变体。
 *
 * 不做完整 HLS 校验：真实站点的 playlist 经常有非标准标签，
 * 解析失败时宁可回退到原始 URL 让播放器自己处理。
 */
object M3u8 {

    fun parse(text: String, baseUrl: String): M3u8Result {
        if (text.isBlank()) return M3u8Result.NotPlaylist
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.none { it.startsWith("#EXTM3U") }) return M3u8Result.NotPlaylist

        val variants = parseVariants(lines, baseUrl)
        if (variants.isNotEmpty()) return M3u8Result.Master(variants)

        val isMedia = lines.any { it.startsWith("#EXTINF") || it.startsWith("#EXT-X-TARGETDURATION") }
        if (!isMedia) return M3u8Result.NotPlaylist

        val encrypted = lines.any {
            it.startsWith("#EXT-X-KEY") && it.contains("METHOD=AES-128", ignoreCase = true)
        }
        return M3u8Result.Media(encrypted)
    }

    private fun parseVariants(lines: List<String>, baseUrl: String): List<Variant> {
        val variants = ArrayList<Variant>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val attrs = parseAttributes(line.substringAfter(':', ""))
                var j = i + 1
                while (j < lines.size && lines[j].startsWith("#")) j++
                if (j < lines.size) {
                    variants += Variant(
                        url = resolve(baseUrl, lines[j]),
                        bandwidth = attrs["BANDWIDTH"]?.toIntOrNull() ?: 0,
                        resolution = attrs["RESOLUTION"],
                    )
                    i = j
                }
            }
            i++
        }
        return variants
    }

    /** `BANDWIDTH=1234,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"` */
    fun parseAttributes(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var i = 0
        while (i < raw.length) {
            val eq = raw.indexOf('=', i)
            if (eq < 0) break
            val key = raw.substring(i, eq).trim().trimStart(',')
            var value: String
            var next: Int
            if (eq + 1 < raw.length && raw[eq + 1] == '"') {
                val end = raw.indexOf('"', eq + 2)
                if (end < 0) break
                value = raw.substring(eq + 2, end)
                next = end + 1
            } else {
                val comma = raw.indexOf(',', eq + 1)
                next = if (comma < 0) raw.length else comma
                value = raw.substring(eq + 1, next)
            }
            result[key] = value
            i = next
        }
        return result
    }

    fun resolve(baseUrl: String, reference: String): String {
        val ref = reference.trim()
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return ref
        return runCatching { base.resolve(ref).toString() }.getOrDefault(ref)
    }
}

fun List<Variant>.bestByBandwidth(): Variant? = maxByOrNull { it.bandwidth }
