package com.dmanime.sniff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CandidatesTest {

    @Test
    fun `classifies common media urls`() {
        assertEquals(MediaKind.HLS, MediaUrls.classify("https://a.example/v.m3u8?token=1"))
        assertEquals(MediaKind.HLS, MediaUrls.classify("https://a.example/v.m3u8#t=10"))
        assertEquals(MediaKind.DASH, MediaUrls.classify("https://a.example/v.mpd"))
        assertEquals(MediaKind.MP4, MediaUrls.classify("https://a.example/v.mp4"))
        assertEquals(MediaKind.BLOB, MediaUrls.classify("blob:https://a.example/1234"))
        assertEquals(MediaKind.UNKNOWN, MediaUrls.classify("https://a.example/api/play?id=1"))
    }

    /**
     * 播放器壳页面不能当流：`player/index.html?url=...index.m3u8` 的 path 是 .html，
     * query 里带 m3u8 只是参数。早期按整串匹配会先拿它去起播，失败后才回退。
     */
    @Test
    fun `player wrapper pages are not mistaken for hls`() {
        assertEquals(
            MediaKind.UNKNOWN,
            MediaUrls.classify("https://m.example/hdst/player/artplayer/index.html?url=https://v.cdn.example/x/index.m3u8"),
        )
        assertEquals(
            MediaKind.HLS,
            MediaUrls.classify("https://v.cdn.example/x/index.m3u8"),
        )
    }

    @Test
    fun `query parameters do not turn a page into a segment`() {
        assertTrue(!MediaUrls.isNoise("https://a.example/watch?poster=cover.jpg"))
        assertTrue(MediaUrls.isNoise("https://a.example/seg1.ts?token=1"))
    }

    @Test
    fun `filters segments images and ad hosts`() {
        assertTrue(MediaUrls.isNoise("https://a.example/seg1.ts"))
        assertTrue(MediaUrls.isNoise("https://a.example/seg1.m4s"))
        assertTrue(MediaUrls.isNoise("https://a.example/cover.jpg"))
        assertTrue(MediaUrls.isNoise("https://doubleclick.net/x/v.mp4"))
        assertTrue(MediaUrls.isNoise("https://a.example/ads/preroll.m3u8"))
        assertTrue(!MediaUrls.isNoise("https://a.example/master.m3u8"))
    }

    @Test
    fun `deduplicates and ranks hls above mp4`() {
        val collector = CandidateCollector()
        assertTrue(collector.offer("https://a.example/v.mp4") != null)
        assertNull(collector.offer("https://a.example/v.mp4"))
        assertTrue(collector.offer("https://a.example/v.m3u8") != null)

        assertEquals(2, collector.size())
        assertEquals("https://a.example/v.m3u8", collector.best()?.url)
        assertEquals(listOf(MediaKind.HLS, MediaKind.MP4), collector.all().map { it.kind })
    }

    @Test
    fun `stops once an hls playlist is found`() {
        val collector = CandidateCollector()
        collector.offer("https://a.example/v.mp4")
        assertTrue(!collector.shouldStop())
        collector.offer("https://a.example/v.m3u8")
        assertTrue(collector.shouldStop())
    }

    @Test
    fun `respects max candidates`() {
        val collector = CandidateCollector(SniffPolicy(maxCandidates = 2))
        collector.offer("https://a.example/1.mp4")
        collector.offer("https://a.example/2.mp4")
        assertNull(collector.offer("https://a.example/3.mp4"))
    }
}
