package com.dmanime.rules.engine

import com.dmanime.rules.model.ChapterFormats
import com.dmanime.rules.model.RuleModes
import com.dmanime.rules.model.parseRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 拿真实的 KazumiRules 快照做兼容性回归。
 *
 * 快照在 `.scratch/kazumirules`（gitignore，不随仓库分发）。没有快照时跳过，
 * 因此 CI 不会因为缺数据而红；本地分析规则时必须跑通。
 *
 * 断言里的数字是 2026-10-18 那次快照的实测值，快照更新后需要同步。
 */
class SchemaConformanceTest {

    private val root = File(System.getProperty("repo.root") ?: ".", ".scratch/kazumirules")

    private fun ruleFiles(): List<File> =
        root.listFiles { f -> f.isFile && f.extension == "json" && f.name != "index.json" }!!
            .sortedBy { it.name }

    @Test
    fun `parses every rule in the snapshot`() {
        assumeTrue(root.isDirectory, "规则快照不存在，跳过: $root")
        val files = ruleFiles()

        assertEquals(86, files.size, "快照规则数变了，请同步断言")

        var xpathOnly = 0
        var apiInvolved = 0
        var deprecated = 0
        var webview = 0

        for (file in files) {
            val rule = parseRule(file.readText())
            assertTrue(rule.name.isNotEmpty(), "规则名缺失: ${file.name}")
            assertTrue(rule.baseUrl.isNotEmpty(), "baseURL 缺失: ${file.name}")
            if (rule.usesApiSearch || rule.usesApiChapters) apiInvolved++ else xpathOnly++
            if (rule.deprecated) deprecated++
            if (rule.useWebview) webview++
        }

        assertEquals(77, xpathOnly, "纯 XPath 规则数")
        assertEquals(9, apiInvolved, "用到 API 的规则数")
        assertEquals(69, deprecated, "已标记废弃的规则数")
        // 有一条规则没写 useWebview，默认值为 true，所以是 86 而不是 85。
        assertEquals(86, webview, "启用 WebView 的规则数")
    }

    /**
     * 快照里真实存在的坏规则：`chapterRoads` 写成了 `//`，不是合法 XPath。
     * 它仍然挂在 index.json 里（未标 deprecated），说明「存活」只代表还在索引，
     * 不代表能跑。留成清单而不是删掉，是为了让这个事实一直可见。
     */
    private val knownBrokenLiveRules = setOf("aafun.json")

    @Test
    fun `all live rules pass validation`() {
        assumeTrue(root.isDirectory, "规则快照不存在，跳过: $root")

        val problems = LinkedHashMap<String, List<RuleIssue>>()
        for (file in ruleFiles()) {
            val rule = parseRule(file.readText())
            if (rule.deprecated) continue
            val issues = RuleValidator.validate(rule)
            if (issues.isNotEmpty()) problems[file.name] = issues
        }

        val unexpected = problems.filterKeys { it !in knownBrokenLiveRules }
        assertTrue(unexpected.isEmpty(), "存活规则里出现了新的校验问题: $unexpected")
        assertEquals(knownBrokenLiveRules, problems.keys, "已知坏规则清单需要同步")
    }

    @Test
    fun `real api level 9 rule keeps its schema`() {
        assumeTrue(root.isDirectory, "规则快照不存在，跳过: $root")

        val rule = parseRule(File(root, "LMM.json").readText())
        assertEquals("9", rule.api)
        assertEquals(RuleModes.API, rule.searchMode)
        assertEquals(RuleModes.XPATH, rule.chapterMode)
        assertTrue(rule.searchApiConfig.sourceTemplate.contains("@source"), rule.searchApiConfig.sourceTemplate)
        assertEquals("\$.list[*]", rule.searchApiConfig.listPath)
    }

    @Test
    fun `real nested api rule keeps chapter format`() {
        assumeTrue(root.isDirectory, "规则快照不存在，跳过: $root")

        val rule = parseRule(File(root, "TvTFun.json").readText())
        assertEquals(ChapterFormats.NESTED, rule.chapterApiConfig.format)
        assertTrue(rule.chapterApiConfig.variables.containsKey("slug"))
        assertEquals("@roadIndex", rule.chapterApiConfig.episodePage?.query?.get("source")?.toString()?.trim('"'))
    }

    @Test
    fun `real post rule keeps json body`() {
        assumeTrue(root.isDirectory, "规则快照不存在，跳过: $root")

        val rule = parseRule(File(root, "xfdmnext.json").readText())
        assertEquals("POST", rule.searchApiConfig.request.method)
        assertEquals("json", rule.searchApiConfig.request.bodyType)
        assertTrue(rule.searchApiConfig.request.headers.isNotEmpty())
    }
}
