package com.dmanime.sniff

data class PlaybackMedia(
    val url: String,
    val headers: Map<String, String>,
    val kind: MediaKind,
    val encrypted: Boolean = false,
    val variants: List<Variant> = emptyList(),
)

/**
 * 把嗅探到的候选地址变成「播放器能直接吃的地址」。
 *
 * m3u8 可能是 master playlist。默认**保持 master 原样**交给播放器：
 * ExoPlayer 的 HLS 实现自带 ABR，会从低码率起播再逐步升档。早期版本在这里
 * 预先选最高码率，结果在模拟器/低端机上第一帧就卡住——实测踩到的坑。
 *
 * 变体列表仍然返回，留给"手动选清晰度"用；需要强制某个档位时把
 * [pickHighestVariant] 打开。
 */
class SniffResolver(
    private val fetch: (String) -> String,
    private val pickHighestVariant: Boolean = false,
) {

    fun resolve(
        candidate: SniffCandidate,
        referer: String = "",
        userAgent: String = "",
    ): PlaybackMedia {
        val headers = buildMap {
            if (referer.isNotBlank()) put("Referer", referer)
            if (userAgent.isNotBlank()) put("User-Agent", userAgent)
        }

        if (candidate.kind != MediaKind.HLS) {
            return PlaybackMedia(candidate.url, headers, candidate.kind)
        }

        val body = runCatching { fetch(candidate.url) }.getOrNull()
            ?: return PlaybackMedia(candidate.url, headers, MediaKind.HLS)

        return when (val parsed = M3u8.parse(body, candidate.url)) {
            is M3u8Result.Master -> {
                val target = if (pickHighestVariant) {
                    parsed.variants.bestByBandwidth()?.url ?: candidate.url
                } else {
                    candidate.url
                }
                PlaybackMedia(target, headers, MediaKind.HLS, variants = parsed.variants)
            }

            is M3u8Result.Media -> PlaybackMedia(
                url = candidate.url,
                headers = headers,
                kind = MediaKind.HLS,
                encrypted = parsed.encrypted,
            )

            M3u8Result.NotPlaylist -> PlaybackMedia(candidate.url, headers, MediaKind.HLS)
        }
    }
}
