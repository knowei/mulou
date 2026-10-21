package com.dmanime.sniff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SniffResolverTest {

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720
        720/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080
        1080/index.m3u8
    """.trimIndent()

    /**
     * 默认保持 master，让 ExoPlayer 自己做 ABR。
     * 早期实现选最高码率，模拟器上首帧就卡住——这条断言锁住修正后的行为。
     */
    @Test
    fun `keeps master playlist so the player can adapt`() {
        val resolver = SniffResolver(fetch = { master })
        val media = resolver.resolve(
            SniffCandidate("https://cdn.example.com/live/play.m3u8", MediaKind.HLS),
            referer = "https://site.example/watch/1",
            userAgent = "UA",
        )

        assertEquals("https://cdn.example.com/live/play.m3u8", media.url)
        assertEquals("https://site.example/watch/1", media.headers["Referer"])
        assertEquals("UA", media.headers["User-Agent"])
        assertEquals(2, media.variants.size)
    }

    @Test
    fun `can force the highest variant when asked`() {
        val resolver = SniffResolver(fetch = { master }, pickHighestVariant = true)
        val media = resolver.resolve(
            SniffCandidate("https://cdn.example.com/live/play.m3u8", MediaKind.HLS),
        )
        assertEquals("https://cdn.example.com/live/1080/index.m3u8", media.url)
        assertEquals(2, media.variants.size)
    }

    @Test
    fun `media playlist keeps original url and flags encryption`() {
        val body = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:9,\ns1.ts"
        val media = SniffResolver(fetch = { body }).resolve(
            SniffCandidate("https://cdn.example.com/live/index.m3u8", MediaKind.HLS),
        )

        assertEquals("https://cdn.example.com/live/index.m3u8", media.url)
        assertTrue(media.encrypted)
    }

    @Test
    fun `probe failure falls back to the sniffed url`() {
        val resolver = SniffResolver(fetch = { throw IllegalStateException("network") })
        val media = resolver.resolve(
            SniffCandidate("https://cdn.example.com/live/play.m3u8", MediaKind.HLS),
        )
        assertEquals("https://cdn.example.com/live/play.m3u8", media.url)
    }

    @Test
    fun `non hls candidates skip probing entirely`() {
        var called = false
        val resolver = SniffResolver(fetch = { called = true; "" })
        val media = resolver.resolve(SniffCandidate("https://cdn.example.com/v.mp4", MediaKind.MP4))

        assertFalse(called)
        assertEquals(MediaKind.MP4, media.kind)
    }
}
