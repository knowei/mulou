package com.dmanime.rules.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MacCmsPlayerTest {

    /** 取自叽哔动漫真实播放页（片段），url 就是 m3u8。 */
    private val jibi = """
        <div class="player-box-main">
        <script type="text/javascript">var player_aaaa={"flag":"play","encrypt":0,"trysee":0,"points":0,
        "link":"\/index.php\/vod\/play\/id\/12863\/sid\/1\/nid\/1.html",
        "link_next":"\/index.php\/vod\/play\/id\/12863\/sid\/3\/nid\/2.html",
        "vod_data":{"vod_name":"\u732b\u4e0e\u9f99","vod_class":"\u65e5\u97e9\u52a8\u6f2b"},
        "url":"https:\/\/play.xluuss.com\/play\/elYRn2ga\/index.m3u8",
        "url_next":"https:\/\/play.xluuss.com\/play\/b2k860Kd\/index.m3u8",
        "from":"xlm3u8","server":"","note":"","id":"12863","sid":3,"nid":1}</script>
        </div>
    """.trimIndent()

    @Test
    fun `reads plain m3u8 from player config`() {
        assertEquals(
            "https://play.xluuss.com/play/elYRn2ga/index.m3u8",
            MacCmsPlayer.embeddedMediaUrl(jibi),
        )
    }

    @Test
    fun `works without a following script close tag`() {
        val html = """<script>var player_aaaa={"encrypt":0,"url":"https://a.example/x.m3u8"};</script>"""
        assertEquals("https://a.example/x.m3u8", MacCmsPlayer.embeddedMediaUrl(html))
    }

    @Test
    fun `encrypted url is left to the sniffer`() {
        val html = """var player_aaaa={"encrypt":1,"url":"aBcD=="};</script>"""
        assertNull(MacCmsPlayer.embeddedMediaUrl(html))
    }

    @Test
    fun `player shell url is not treated as media`() {
        val html = """var player_aaaa={"encrypt":0,"url":"/static/player/xlm3u8.html?url=a.m3u8"};</script>"""
        assertNull(MacCmsPlayer.embeddedMediaUrl(html, "https://site.example/vod/play/1.html"))
    }

    @Test
    fun `relative media url is resolved against the page`() {
        val html = """var player_aaaa={"encrypt":0,"url":"/hls/1/index.m3u8"};</script>"""
        assertEquals(
            "https://site.example/hls/1/index.m3u8",
            MacCmsPlayer.embeddedMediaUrl(html, "https://site.example/vod/play/1.html"),
        )
    }

    @Test
    fun `pages without player config return null`() {
        assertNull(MacCmsPlayer.embeddedMediaUrl("<html><body>没有播放器</body></html>"))
        assertNull(MacCmsPlayer.embeddedMediaUrl("var player_aaaa=not-json;</script>"))
    }
}
