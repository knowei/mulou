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

    @Test
    fun `missing detail does not invent numbers`() {
        assertEquals("", AiringState.summary(null))
        assertEquals("12 集", AiringState.summary(null, sourceEpisodes = 12))
        assertEquals(0, AiringState.airedCount(null))
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
