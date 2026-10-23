package com.dmanime.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 版本标签的识别：宁可没有，不要贴错。 */
class TitleVersionTest {

    @Test
    fun `chinese season number`() {
        assertEquals("第2季", TitleVersion.seasonLabel("葬送的芙莉莲 第二季"))
        assertEquals("第3季", TitleVersion.seasonLabel("药屋少女的呢喃 第三季"))
        assertEquals("第10季", TitleVersion.seasonLabel("某番 第十季"))
    }

    @Test
    fun `arabic season number`() {
        assertEquals("第2季", TitleVersion.seasonLabel("葬送的芙莉莲 第2季"))
        assertEquals("第2季", TitleVersion.seasonLabel("葬送的芙莉莲 2期"))
    }

    @Test
    fun `english season forms`() {
        assertEquals("第2季", TitleVersion.seasonLabel("Mushoku Tensei Season 2"))
        assertEquals("第2季", TitleVersion.seasonLabel("Mushoku Tensei 2nd Season"))
        assertEquals("第3季", TitleVersion.seasonLabel("Attack on Titan S3"))
    }

    @Test
    fun `movie and ova`() {
        assertEquals("剧场版", TitleVersion.seasonLabel("仙逆剧场版 弑仙之战"))
        assertEquals("剧场版", TitleVersion.seasonLabel("劇場版 チェンソーマン レゼ篇"))
        assertEquals("OVA", TitleVersion.seasonLabel("某番 OVA"))
        assertEquals("OAD", TitleVersion.seasonLabel("某番 OAD"))
    }

    @Test
    fun `final season beats plain season`() {
        assertEquals("最终季", TitleVersion.seasonLabel("进击的巨人 最终季"))
        assertEquals("特别篇", TitleVersion.seasonLabel("某番 特别篇"))
    }

    @Test
    fun `movie word prefix`() {
        assertEquals("剧场版", TitleVersion.seasonLabel("电影 黑色五叶草 魔法帝之剑"))
        assertEquals("剧场版", TitleVersion.seasonLabel("电影：某某"))
        // 《电影少女》是正片名字，不能因为开头两个字就贴剧场版
        assertNull(TitleVersion.seasonLabel("电影少女"))
    }

    @Test
    fun `plain titles get nothing`() {
        assertNull(TitleVersion.seasonLabel("葬送的芙莉莲"))
        assertNull(TitleVersion.seasonLabel("东京复仇者 三天战争篇"))
        assertNull(TitleVersion.seasonLabel(""))
        // 名字里正好带英文 s+数字，不能误判成季数
        assertNull(TitleVersion.seasonLabel("Re:Zero kara Hajimeru Isekai Seikatsu"))
    }
}
