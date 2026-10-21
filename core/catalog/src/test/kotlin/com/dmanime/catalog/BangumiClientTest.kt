package com.dmanime.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BangumiClientTest {

    private val fixture = javaClass.getResourceAsStream("/fixtures/calendar.json")!!
        .bufferedReader().readText()

    private val client = BangumiClient { requestUrl ->
        assertTrue(requestUrl.endsWith("/calendar"), requestUrl)
        fixture
    }

    @Test
    fun `parses calendar and sorts by weekday id`() {
        val days = client.calendar()

        assertEquals(2, days.size)
        assertEquals(1, days[0].weekday.id)
        assertEquals("星期一", days[0].weekday.cn)
        assertEquals(2, days[0].items.size)
        assertEquals(5, days[1].weekday.id)
    }

    @Test
    fun `chinese title wins and falls back to original name`() {
        val days = client.calendar()

        assertEquals("葬送的芙莉莲", days[0].items[0].displayTitle)
        assertEquals("名もなき作品", days[0].items[1].displayTitle)
    }

    @Test
    fun `covers are upgraded to https for android`() {
        val subject = client.calendar()[0].items[0]

        assertTrue(subject.coverUrl.startsWith("https://"), subject.coverUrl)
        assertEquals("https://lain.bgm.tv/pic/cover/l/1.jpg", subject.coverUrl)
    }

    @Test
    fun `subject without images yields empty cover`() {
        val subject = client.calendar()[0].items[1]
        assertEquals("", subject.coverUrl)
        assertEquals("", subject.coverUrlSmall)
    }

    /**
     * 列表必须用中等尺寸：原图有的一张 2 MB，一屏十几张会把反代打爆
     * （FC 单实例并发为 1，实测只有先请求的几张能出图）。
     */
    @Test
    fun `list cover prefers the smaller variant`() {
        val subject = client.calendar()[0].items[0]

        assertTrue(subject.coverUrl.endsWith("/l/1.jpg"), subject.coverUrl)
        assertTrue(subject.coverUrlSmall.endsWith("/c/1.jpg"), subject.coverUrlSmall)
        assertTrue(subject.coverUrlSmall.startsWith("https://"), subject.coverUrlSmall)
    }

    @Test
    fun `today picks the matching weekday`() {
        val days = client.calendar()

        assertEquals(1, days.today(5)?.items?.size)
        assertNull(days.today(3))
    }

    @Test
    fun `rating score is parsed`() {
        assertEquals(9.2, client.calendar()[0].items[0].rating.score, 0.001)
    }

    @Test
    fun `subject detail exposes episodes tags and rating count`() {
        val text = javaClass.getResourceAsStream("/fixtures/subject.json")!!
            .bufferedReader().readText()
        val detail = BangumiClient { text }.parseSubject(text)

        assertEquals("葬送的芙莉莲", detail.displayTitle)
        assertEquals("全 28 集", detail.episodeLabel)
        assertEquals(51234, detail.rating.total)
        assertEquals(listOf("奇幻", "冒险", "治愈"), detail.tags.map { it.name })
        assertTrue(detail.coverUrl.startsWith("https://"), detail.coverUrl)
    }

    /**
     * 线上真实数据里 `images` / `rating` / `name_cn` 都可能是 null，
     * 只要有一条 null 没兜住，kotlinx 会丢掉整个列表、首页直接空白。
     */
    @Test
    fun `null fields fall back to defaults instead of failing the whole list`() {
        val text = javaClass.getResourceAsStream("/fixtures/calendar_with_nulls.json")!!
            .bufferedReader().readText()

        val days = BangumiClient { text }.calendar()

        assertEquals(1, days.size)
        val subject = days[0].items[0]
        assertEquals("作品A", subject.displayTitle)
        assertEquals(0.0, subject.rating.score, 0.001)
        assertEquals("", subject.coverUrl)
    }

    @Test
    fun `search results map to subjects`() {
        val text = javaClass.getResourceAsStream("/fixtures/search.json")!!
            .bufferedReader().readText()
        val response = BangumiClient { text }.parseSearch(text)

        assertEquals(24, response.total)
        val first = response.data.first()
        assertEquals("碧蓝之海", first.displayTitle)
        assertEquals(235130, first.toSubject().id)
        assertEquals("2018-07-13", first.toSubject().airDate)
        assertTrue(first.toSubject().coverUrl.startsWith("https://"))
    }
}
