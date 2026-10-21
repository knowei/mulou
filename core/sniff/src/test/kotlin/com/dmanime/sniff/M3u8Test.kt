package com.dmanime.sniff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class M3u8Test {

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
        720/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080
        1080/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360
        https://other.cdn.example.com/360/index.m3u8
    """.trimIndent()

    private val media = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:10
        #EXT-X-KEY:METHOD=AES-128,URI="https://cdn.example.com/key.bin"
        #EXTINF:9.9,
        seg1.ts
        #EXTINF:9.9,
        seg2.ts
    """.trimIndent()

    @Test
    fun `master playlist exposes variants with resolved urls`() {
        val result = M3u8.parse(master, "https://cdn.example.com/live/play.m3u8")
        val parsed = result as M3u8Result.Master

        assertEquals(3, parsed.variants.size)
        assertEquals(
            "https://cdn.example.com/live/1080/index.m3u8",
            parsed.variants.bestByBandwidth()?.url,
        )
        assertEquals("1920x1080", parsed.variants.bestByBandwidth()?.resolution)
    }

    @Test
    fun `absolute variant urls are kept`() {
        val parsed = M3u8.parse(master, "https://cdn.example.com/live/play.m3u8") as M3u8Result.Master
        assertTrue(parsed.variants.any { it.url == "https://other.cdn.example.com/360/index.m3u8" })
    }

    @Test
    fun `media playlist is detected and aes flagged`() {
        val parsed = M3u8.parse(media, "https://cdn.example.com/live/index.m3u8") as M3u8Result.Media
        assertTrue(parsed.encrypted)
    }

    @Test
    fun `html response is not a playlist`() {
        assertEquals(
            M3u8Result.NotPlaylist,
            M3u8.parse("<html><body>403</body></html>", "https://cdn.example.com/a.m3u8"),
        )
    }

    @Test
    fun `attribute parser handles quoted values containing commas`() {
        val attrs = M3u8.parseAttributes("""BANDWIDTH=1,CODECS="avc1,mp4a",RESOLUTION=1x1""")
        assertEquals("1", attrs["BANDWIDTH"])
        assertEquals("avc1,mp4a", attrs["CODECS"])
        assertEquals("1x1", attrs["RESOLUTION"])
    }

    @Test
    fun `plain media playlist without encryption is not flagged`() {
        val plain = media.replace("#EXT-X-KEY:METHOD=AES-128,URI=\"https://cdn.example.com/key.bin\"\n", "")
        val parsed = M3u8.parse(plain, "https://cdn.example.com/live/index.m3u8") as M3u8Result.Media
        assertFalse(parsed.encrypted)
    }
}
