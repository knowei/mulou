package com.dmanime.catalog

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AiringStateTest {

    /** 猫与龙：2026-07-04 首播、12 集，2026-10 早就播完了，但 Bangumi 的 eps 还停在 11。 */
    private val mao = SubjectDetail(
        id = 538760,
        name = "猫と竜",
        nameCn = "猫与龙",
        airDate = "2026-07-04",
        eps = 11,
        totalEpisodes = 12,
        platform = "TV",
    )

    private val today = LocalDate.of(2026, 10, 22)

    @Test
    fun `finished show stops claiming an update in progress`() {
        assertTrue(AiringState.finished(mao, today))
        assertEquals(12, AiringState.airedCount(mao, today))
        assertEquals("共 12 集", AiringState.summary(mao, today = today))
    }

    @Test
    fun `still airing show keeps the update wording`() {
        val airing = mao.copy(eps = 3)
        val mid = LocalDate.of(2026, 7, 20)
        assertFalse(AiringState.finished(airing, mid))
        assertEquals(3, AiringState.airedCount(airing, mid))
        assertEquals("更新至 3 集 / 共 12 集", AiringState.summary(airing, today = mid))
    }

    @Test
    fun `last episode week is not counted as finished early`() {
        val airing = mao.copy(eps = 11)
        // 第 12 集播出于 2026-09-19（首播 + 11 周），当天还不算完结
        assertFalse(AiringState.finished(airing, LocalDate.of(2026, 9, 19)))
        assertTrue(AiringState.finished(airing, LocalDate.of(2026, 9, 20)))
    }

    @Test
    fun `movie and single episode subjects finish on their air date`() {
        val movie = SubjectDetail(id = 1, airDate = "2025-12-19", eps = 1, totalEpisodes = 1, platform = "剧场版")
        assertFalse(AiringState.finished(movie, LocalDate.of(2025, 12, 18)))
        assertTrue(AiringState.finished(movie, LocalDate.of(2025, 12, 19)))
        assertEquals("共 1 集", AiringState.summary(movie))
    }

    @Test
    fun `unknown dates fall back to bangumi eps`() {
        val unknown = mao.copy(airDate = "")
        assertFalse(AiringState.finished(unknown, today))
        assertEquals("更新至 11 集 / 共 12 集", AiringState.summary(unknown, today = today))
    }

    /**
     * Bangumi 上没人勾 `eps` 时，靠首播日数周数就能算出已播集数：
     * 东京复仇者 三天战争篇 10-02 首播，10-22 已经播到第 3 集。
     */
    @Test
    fun `next airing episode fills in the gap`() {
        val ongoing = SubjectDetail(
            id = 1,
            name = "東京リベンジャーズ 三天戦争編",
            airDate = "2026-10-02",
            eps = 0,
            totalEpisodes = 13,
            platform = "TV",
        )
        val today = LocalDate.of(2026, 10, 22)
        assertEquals(3, AiringState.airedCount(ongoing, today))
        assertEquals(3, AiringState.latestEpisode(ongoing, today = today))
        assertEquals(3, AiringState.latestEpisode(ongoing, nextAiring = 4, today = today))
        // 整季都勾满了（eps=13）也不能越过「下一集是第 4 集」这个事实上限
        assertEquals(3, AiringState.latestEpisode(ongoing.copy(eps = 13), nextAiring = 4, today = today))
        // 完结作品仍然以总集数收口，不会被「下一集」带偏
        assertEquals(12, AiringState.latestEpisode(mao, nextAiring = 99, today = LocalDate.of(2026, 12, 1)))
    }

    @Test
    fun `missing detail does not invent numbers`() {
        assertEquals("", AiringState.summary(null))
        assertEquals("12 集", AiringState.summary(null, sourceEpisodes = 12))
        assertEquals(0, AiringState.airedCount(null))
    }

    /**
     * 复现用户报的问题：「乱世千金」共 25 集、10-06 才开播，Bangumi 的 `eps` 却已经
     * 写满 25，于是详情页选集里第 4~25 集全亮着可点，点进去必然是"源还没更新"。
     */
    @Test
    fun `planned episode count is not mistaken for aired count`() {
        val fresh = SubjectDetail(
            id = 2,
            name = "乱世千金ニヴァ・リストン",
            airDate = "2026-10-06",
            eps = 25,
            totalEpisodes = 25,
            platform = "TV",
        )
        val today = LocalDate.of(2026, 10, 24)
        assertFalse(AiringState.finished(fresh, today))
        assertEquals(3, AiringState.airedCount(fresh, today))
        assertEquals(3, AiringState.latestEpisode(fresh, today = today))
        assertEquals("更新至 3 集 / 共 25 集", AiringState.summary(fresh, today = today))
    }

    /** 网络放送、OVA 这类多是攒着一次上，不按每周一集去猜。 */
    @Test
    fun `irregular platforms do not use the weekly math`() {
        val web = SubjectDetail(
            id = 3,
            airDate = "2026-10-06",
            eps = 6,
            totalEpisodes = 12,
            platform = "WEB",
        )
        assertEquals(6, AiringState.airedCount(web, LocalDate.of(2026, 10, 24)))
    }

    /**
     * 详情接口 `GET /v0/subjects/{id}` 用的是 `date`，不是 `air_date`。
     * 早先这里只认 `air_date`，导致 `airDate` 永远为空、`finished()` 永远 false，
     * 于是"约定的梦幻岛 第二季"播完了还写着"更新至 11 集 / 共 12 集"。
     */
    @Test
    fun `detail json exposes the date field`() {
        val body = """
            {"id":279202,"name":"約束のネバーランド Season 2","name_cn":"约定的梦幻岛 第二季",
             "date":"2021-01-07","eps":11,"total_episodes":12,"platform":"TV"}
        """.trimIndent()
        val detail = Json { ignoreUnknownKeys = true }.decodeFromString<SubjectDetail>(body)
        assertEquals("2021-01-07", detail.effectiveAirDate)
        assertTrue(AiringState.finished(detail, LocalDate.of(2021, 4, 1)))
    }
}
