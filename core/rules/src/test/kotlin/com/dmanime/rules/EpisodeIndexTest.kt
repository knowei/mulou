package com.dmanime.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EpisodeIndexTest {

    @Test
    fun `剧场版只有一集且叫 HD中字 时匹配第 1 集`() {
        assertEquals(0, EpisodeIndex.match(listOf("HD中字"), 1, 1))
    }

    @Test
    fun `正片这类没有集号的单集线路也能匹配第 1 集`() {
        assertEquals(0, EpisodeIndex.match(listOf("正片"), 1, 1))
    }

    @Test
    fun `有集号时严格按集号找`() {
        val names = listOf("第1集", "第2集", "第3集")
        assertEquals(2, EpisodeIndex.match(names, 3, 3))
        assertEquals(1, EpisodeIndex.match(names, 3, 2))
    }

    @Test
    fun `源只到第 4 集时第 5 集返回 null 而不是退到最后一集`() {
        val names = listOf("第1集", "第2集", "第3集", "第4集")
        assertNull(EpisodeIndex.match(names, 4, 5))
    }

    @Test
    fun `没有集号的线路集数不够时同样返回 null`() {
        assertNull(EpisodeIndex.match(listOf("HD中字"), 1, 2))
    }

    @Test
    fun `半集不顶替整数集`() {
        val names = listOf("第4集", "第5集", "第5.5集", "第6集")
        assertEquals(1, EpisodeIndex.match(names, 4, 5))
        assertEquals(3, EpisodeIndex.match(names, 4, 6))
    }

    @Test
    fun `分辨率不算集号`() {
        assertNull(EpisodeIndex.parseNo("HD1080P"))
        assertNull(EpisodeIndex.parseNo("4K 2160"))
        assertEquals(7.0, EpisodeIndex.parseNo("EP07"))
    }

    @Test
    fun `名字里带分辨率也不影响单集线路按顺序推断`() {
        assertEquals(0, EpisodeIndex.match(listOf("HD1080P"), 1, 1))
    }

    @Test
    fun `集名为空时按顺序推断`() {
        val names = listOf(null, "", "")
        assertEquals(1, EpisodeIndex.match(names, 3, 2))
        assertNull(EpisodeIndex.match(names, 3, 4))
    }
}
