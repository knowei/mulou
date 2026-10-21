package com.dmanime.danmaku

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * UGC 匹配器的测试。
 *
 * 用例全部取自真实搜索结果（猫与龙、葬送的芙莉莲），包括那些"看着像但其实是坑"的标题：
 * 名字反着写的、解说向的、整季塞一个视频的。这些正是要靠结构校验挡掉的东西。
 */
class UgcMatcherTest {

    @Test
    fun `番剧名被包含就算命中`() {
        assertTrue(UgcMatcher.titleOk("猫与龙", "【猫与龙】全12集 超清中字（未删减版）"))
        assertTrue(UgcMatcher.titleOk("葬送的芙莉莲", "【4K】葬送的芙莉莲 全28话"))
    }

    @Test
    fun `名字顺序反了不认`() {
        // "龙与猫" 和 "猫与龙" 是同一部片，但标题层面判不出来，宁可漏也不能错
        assertFalse(UgcMatcher.titleOk("猫与龙", "𝟒𝐊臻享版【龙与猫】全12集 超清中字（未删减版）"))
    }

    @Test
    fun `毫不相干的标题不认`() {
        assertFalse(UgcMatcher.titleOk("猫与龙", "【孤独摇滚】全12集"))
    }

    @Test
    fun `版本标记不一致不认`() {
        assertFalse(UgcMatcher.titleOk("猫与龙", "【猫与龙】第二季 全12集"))
        assertTrue(UgcMatcher.titleOk("猫与龙 第二季", "【猫与龙】第二季 全12集"))
    }

    @Test
    fun `集数吻合得分最高`() {
        val twelve = List(12) { 1948 }
        assertEquals(30, UgcMatcher.structureScore(twelve, 12))
    }

    @Test
    fun `差一集仍可用但降分`() {
        val thirteen = List(13) { 1450 }
        assertEquals(10, UgcMatcher.structureScore(thirteen, 12))
    }

    @Test
    fun `集数差太多直接出局`() {
        val single = listOf(3_600)
        assertEquals(-1000, UgcMatcher.structureScore(single, 12))
    }

    @Test
    fun `切片视频出局`() {
        // 一堆 2 分钟的切片，看着"多 P"，其实是剪辑号
        val clips = List(30) { 120 }
        assertEquals(-1000, UgcMatcher.structureScore(clips, 12))
    }

    @Test
    fun `整季塞一个视频出局`() {
        // 23376 秒 ≈ 6.5 小时，是"全12集合集"而不是分 P
        assertEquals(-1000, UgcMatcher.structureScore(listOf(23_376), 12))
    }

    @Test
    fun `剧场版单集可以过`() {
        assertEquals(30, UgcMatcher.structureScore(listOf(6_000), 1))
    }

    @Test
    fun `集数未知时只校验结构`() {
        assertEquals(20, UgcMatcher.structureScore(List(12) { 1450 }, 0))
    }

    @Test
    fun `集数对得上但每集只有七分钟的剪辑版出局`() {
        // 「迷宫饭」UGC 区第一名：24 个分 P、每个 462 秒，集数正好对得上，
        // 但是剪辑/加速版，弹幕时间轴会一路错位
        assertEquals(-1000, UgcMatcher.structureScore(List(24) { 462 }, 24))
    }

    @Test
    fun `候选按播放量排序`() {
        val videos = listOf(
            UgcVideo("BV1", "【猫与龙】全12集 低清", 141, 23_376),
            UgcVideo("BV2", "【猫与龙】全12集 超清", 1_450_542, 23_376),
            UgcVideo("BV3", "【猫与龙】全12集 中清", 6_700, 23_376),
            UgcVideo("BV4", "【别的番】全12集", 9_999_999, 23_376),
        )
        val ranked = UgcMatcher.rank("猫与龙", videos)
        assertEquals(listOf("BV2", "BV3", "BV1"), ranked.map { it.bvid })
    }

    @Test
    fun `标题多标第一季也算同一部`() {
        // UP 主习惯在标题里写"第一季"，番剧名里并没有，不该因此被否掉
        assertTrue(UgcMatcher.titleOk("猫与龙", "【猫与龙】第一季 全12集 超清中字"))
        assertTrue(UgcMatcher.titleOk("猫与龙", "【猫与龙】第1期 全12集"))
        assertTrue(UgcMatcher.titleOk("猫与龙", "【猫与龙】1st Season 全12集"))
    }

    @Test
    fun `搬运集数少于官方仍可用`() {
        // 芙莉莲官方 36 集，B 站只买了 28 集，搬运也只有 28 个分 P
        val twentyEight = List(28) { 1_440 }
        assertEquals(10, UgcMatcher.structureScore(twentyEight, 36))
    }

    @Test
    fun `搬运只到一半就不认`() {
        val twelve = List(12) { 1_440 }
        assertEquals(-1000, UgcMatcher.structureScore(twelve, 36))
    }
}
