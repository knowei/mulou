package com.dmanime.rules

import com.dmanime.rules.model.AnimeRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuleSyncTest {

    private fun rule(
        name: String,
        baseUrl: String = "https://example.com",
        deprecated: Boolean = false,
    ) = AnimeRule(name = name, baseUrl = baseUrl, deprecated = deprecated)

    @Test
    fun `本地没有的规则算新增`() {
        val diff = RuleSync.diff(
            local = listOf(rule("A")),
            remote = listOf(rule("A"), rule("B")),
        )
        assertEquals(listOf("B"), diff.addedNames)
        assertTrue(diff.updated.isEmpty())
        assertEquals(1, diff.unchanged)
    }

    @Test
    fun `内容不同算更新`() {
        val diff = RuleSync.diff(
            local = listOf(rule("A", "https://old.com")),
            remote = listOf(rule("A", "https://new.com")),
        )
        assertEquals(listOf("A"), diff.updatedNames)
        assertTrue(diff.added.isEmpty())
        assertEquals(0, diff.unchanged)
    }

    @Test
    fun `内容一致算没变化`() {
        val diff = RuleSync.diff(
            local = listOf(rule("A"), rule("B")),
            remote = listOf(rule("A"), rule("B")),
        )
        assertTrue(diff.isEmpty)
        assertEquals(2, diff.unchanged)
    }

    @Test
    fun `本地自建的规则不会被远端带走`() {
        val mine = rule("我的源", "https://mine.local")
        val diff = RuleSync.diff(local = listOf(mine), remote = listOf(rule("A")))
        val merged = RuleSync.apply(listOf(mine), diff)
        assertEquals(listOf("我的源", "A"), merged.map { it.name })
    }

    @Test
    fun `更新时保持原有顺序，新增排到最后`() {
        val local = listOf(rule("A"), rule("B"), rule("C"))
        val diff = RuleSync.diff(
            local = local,
            remote = listOf(rule("A"), rule("B", "https://b2.com"), rule("B2")),
        )
        val merged = RuleSync.apply(local, diff)
        assertEquals(listOf("A", "B", "C", "B2"), merged.map { it.name })
        assertEquals("https://b2.com", merged.first { it.name == "B" }.baseUrl)
    }

    @Test
    fun `远端标记废弃的规则既不新增也不更新`() {
        val diff = RuleSync.diff(
            local = emptyList(),
            remote = listOf(rule("Dead", deprecated = true), rule("Alive")),
        )
        assertEquals(listOf("Alive"), diff.addedNames)
    }

    @Test
    fun `名字为空的脏数据被忽略`() {
        val diff = RuleSync.diff(local = emptyList(), remote = listOf(AnimeRule(name = "")))
        assertTrue(diff.isEmpty)
    }
}
