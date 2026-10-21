package com.dmanime.danmaku

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DanmakuMatcherTest {

    private fun season(
        id: Long,
        title: String,
        original: String = "",
        episodes: Int = 12,
    ) = DanmakuSeason(
        seasonId = id,
        title = title,
        originalTitle = original,
        episodeCount = episodes,
        score = 0.0,
        cover = "",
        typeName = "番剧",
        indexShow = "",
    )

    @Test
    fun `归一化去掉标点与全角差异`() {
        assertEquals("葬送的芙莉莲", DanmakuMatcher.normalize("葬送的芙莉莲"))
        assertEquals("frieren", DanmakuMatcher.normalize("FRIEREN"))
        assertEquals("葬送的芙莉莲", DanmakuMatcher.normalize(" 葬送的芙莉莲！"))
        assertEquals("abc123", DanmakuMatcher.normalize("a b c　１２３"))
    }

    @Test
    fun `完全同名得分最高`() {
        val exact = season(1, "葬送的芙莉莲")
        val other = season(2, "葬送的芙莉莲 第二季")
        assertTrue(
            DanmakuMatcher.score("葬送的芙莉莲", exact) >
                DanmakuMatcher.score("葬送的芙莉莲", other),
        )
    }

    @Test
    fun `另一季不会顶替正片`() {
        val s2 = season(2, "葬送的芙莉莲 第二季", episodes = 28)
        // 用户要的是第一季（28 集），B 站只搜到"第二季"时不该硬套
        assertNull(DanmakuMatcher.best("葬送的芙莉莲", listOf(s2), expectedEpisodes = 28))
    }

    @Test
    fun `同名的第二季能对上第二季`() {
        val s1 = season(1, "葬送的芙莉莲", episodes = 28)
        val s2 = season(2, "葬送的芙莉莲 第二季", episodes = 28)
        val picked = DanmakuMatcher.best("葬送的芙莉莲 第二季", listOf(s1, s2), expectedEpisodes = 28)
        assertEquals(2L, picked?.seasonId)
    }

    @Test
    fun `剧场版不会被正片顶替`() {
        val tv = season(1, "链锯人", episodes = 12)
        val movie = season(2, "链锯人 剧场版 蕾塞篇", episodes = 1)
        assertNull(DanmakuMatcher.best("链锯人 剧场版 蕾塞篇", listOf(tv), expectedEpisodes = 1))
        assertEquals(
            2L,
            DanmakuMatcher.best("链锯人 剧场版 蕾塞篇", listOf(tv, movie), expectedEpisodes = 1)?.seasonId,
        )
    }

    @Test
    fun `集数接近的候选被加分`() {
        val a = season(1, "魔法少女育成计划", episodes = 12)
        val b = season(2, "魔法少女育成计划", episodes = 12)
        val picked = DanmakuMatcher.best("魔法少女育成计划", listOf(a, b), expectedEpisodes = 12)
        assertEquals(1L, picked?.seasonId)
    }

    @Test
    fun `日文原名也能命中`() {
        val s = season(1, "中文名不一样", original = "葬送のフリーレン", episodes = 28)
        val picked = DanmakuMatcher.best("葬送のフリーレン", listOf(s), expectedEpisodes = 28)
        assertEquals(1L, picked?.seasonId)
    }

    @Test
    fun `完全无关时返回 null`() {
        val s = season(1, "孤独摇滚！", episodes = 12)
        assertNull(DanmakuMatcher.best("葬送的芙莉莲", listOf(s), expectedEpisodes = 28))
    }
}
