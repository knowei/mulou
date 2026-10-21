package com.dmanime.sniff

enum class MediaKind { HLS, DASH, MP4, BLOB, UNKNOWN }

/** 嗅探到的一个候选地址。 */
data class SniffCandidate(
    val url: String,
    val kind: MediaKind = MediaKind.UNKNOWN,
    val from: String = "",
)

/** 候选地址的识别与噪声过滤。 */
object MediaUrls {

    private val HLS = Regex("""\.m3u8(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val DASH = Regex("""\.mpd(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val MP4 = Regex("""\.(mp4|m4v)(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val SEGMENT = Regex("""\.(ts|m4s|aac|vtt|srt|jpg|jpeg|png|webp)(\?|#|$)""", RegexOption.IGNORE_CASE)

    private val NOISE_TOKENS = listOf(
        "doubleclick", "googlesyndication", "googletagmanager", "adservice",
        "/ads/", "/ad/", "analytics", "beacon", "sentry", "hotjar",
    )

    fun classify(url: String): MediaKind {
        if (url.startsWith("blob:")) return MediaKind.BLOB
        // 只看 path：`player.html?url=...index.m3u8` 是播放器壳页面，
        // 它的 query 里带 m3u8 不代表它自己是流（实测海星动漫会先命中这种壳）。
        val path = pathOf(url)
        return when {
            HLS.containsMatchIn(path) -> MediaKind.HLS
            DASH.containsMatchIn(path) -> MediaKind.DASH
            MP4.containsMatchIn(path) -> MediaKind.MP4
            else -> MediaKind.UNKNOWN
        }
    }

    /**
     * 分片（.ts/.m4s）、图片、广告/统计域名都不是我们要的"播放入口"。
     * 宁可漏抓一个分片，也不要把广告地址排到前面。
     */
    fun isNoise(url: String): Boolean {
        if (url.isBlank()) return true
        if (SEGMENT.containsMatchIn(pathOf(url))) return true
        val lower = url.lowercase()
        return NOISE_TOKENS.any { lower.contains(it) }
    }

    /** 取 `scheme://host` 之后的部分，忽略 query 与 fragment。 */
    private fun pathOf(url: String): String {
        val cut = url.indexOfFirst { it == '?' || it == '#' }
        val head = if (cut >= 0) url.substring(0, cut) else url
        val schemeEnd = head.indexOf("://")
        if (schemeEnd < 0) return head
        val slash = head.indexOf('/', schemeEnd + 3)
        return if (slash < 0) "" else head.substring(slash)
    }

    fun score(candidate: SniffCandidate): Int = when (candidate.kind) {
        MediaKind.HLS -> 100
        MediaKind.DASH -> 80
        MediaKind.MP4 -> 60
        MediaKind.BLOB -> 30
        MediaKind.UNKNOWN -> 0
    }
}

data class SniffPolicy(
    val maxCandidates: Int = 50,
    /** 抓到第一条 HLS 就认为够用了；广告 m3u8 很少见，先按这个策略走。 */
    val stopOnFirstHls: Boolean = true,
)

class CandidateCollector(private val policy: SniffPolicy = SniffPolicy()) {

    private val seen = LinkedHashSet<String>()
    private val candidates = mutableListOf<SniffCandidate>()

    fun offer(url: String, kind: MediaKind = MediaUrls.classify(url), from: String = ""): SniffCandidate? {
        val trimmed = url.trim()
        if (MediaUrls.isNoise(trimmed)) return null
        if (!seen.add(trimmed)) return null
        if (candidates.size >= policy.maxCandidates) return null
        val candidate = SniffCandidate(trimmed, kind, from)
        candidates += candidate
        return candidate
    }

    fun all(): List<SniffCandidate> = candidates.sortedByDescending { MediaUrls.score(it) }

    fun best(): SniffCandidate? = all().firstOrNull { it.kind != MediaKind.UNKNOWN }

    fun hasHls(): Boolean = candidates.any { it.kind == MediaKind.HLS }

    fun size(): Int = candidates.size

    fun shouldStop(): Boolean = policy.stopOnFirstHls && hasHls()

    fun reset() {
        seen.clear()
        candidates.clear()
    }
}
