package com.dmanime.rules.engine

import com.dmanime.rules.error.RuleFormatException
import com.dmanime.rules.model.parseRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApiStrategyTest {

    private val strategy = ApiStrategy()

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText()

    private val searchRule = parseRule(
        """
        {
          "name": "api-search",
          "baseURL": "https://site.example/",
          "searchMode": "api",
          "searchApiConfig": {
            "request": {
              "method": "GET",
              "url": "https://site.example/api/search",
              "query": {"q": "@keyword", "pageSize": 20}
            },
            "listPath": "$.list[*]",
            "namePath": "$.name",
            "sourcePath": "$.id",
            "sourceTemplate": "https://site.example/GV@source/"
          }
        }
        """.trimIndent()
    )

    @Test
    fun `request template encodes keyword and keeps numeric query values`() {
        val request = strategy.prepareSearchRequest(searchRule.searchApiConfig, mapOf("keyword" to "芙莉莲"))
        val url = request.resolvedUrl()

        assertTrue(url.startsWith("https://site.example/api/search?"), url)
        assertTrue(url.contains("pageSize=20"), url)
        assertTrue(url.contains("q=%E8%8A%99%E8%8E%89%E8%8E%B2"), url)
        assertFalse(url.contains("芙"), url)
    }

    @Test
    fun `source template is expanded without url encoding of slashes`() {
        val (items, diagnostics) = strategy.parseSearch(fixture("api_search.json"), searchRule.searchApiConfig)

        assertEquals(2, items.size)
        assertEquals("作品A", items[0].name)
        assertEquals("https://site.example/GV101/", items[0].src)
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `nested chapters expand variables and episode page query`() {
        val rule = parseRule(
            """
            {
              "name": "nested",
              "baseURL": "https://site.example/",
              "chapterMode": "api",
              "chapterApiConfig": {
                "request": {"method": "GET", "url": "https://site.example/api/detail/@source"},
                "format": "nested",
                "roadsPath": "$.data.playSources[*]",
                "roadNamePath": "$.name",
                "episodesPath": "$.episodes[*]",
                "episodeNamePath": "$.name",
                "episodeUrlPath": "$.url",
                "variables": {"slug": "$.data.slug"},
                "episodePage": {
                  "url": "https://site.example/watch/@slug",
                  "query": {"line": "@roadIndex", "ep": "@episodeIndex"}
                }
              }
            }
            """.trimIndent()
        )

        val (roads, diagnostics) = strategy.parseChapters(
            fixture("api_chapters_nested.json"),
            rule.chapterApiConfig,
            source = "101",
            baseUrl = rule.baseUrl,
        )

        assertEquals(2, roads.size)
        assertEquals(listOf("线路A", "线路B"), roads.map { it.name })
        assertEquals(
            listOf(
                "https://site.example/watch/abc?line=0&ep=0",
                "https://site.example/watch/abc?line=0&ep=1",
            ),
            roads[0].urls,
        )
        assertEquals(listOf("第1集", "第2集"), roads[0].names)
        assertEquals(listOf("https://site.example/watch/abc?line=1&ep=0"), roads[1].urls)
        assertTrue(diagnostics.isEmpty(), diagnostics.toString())
    }

    @Test
    fun `delimited chapters parse maccms triple separator`() {
        val rule = parseRule(
            """
            {
              "name": "maccms",
              "baseURL": "https://site.example/",
              "chapterMode": "api",
              "chapterApiConfig": {
                "request": {"method": "GET", "url": "https://site.example/api/detail/@source"},
                "format": "delimited",
                "roadNamesPath": "$.list[0].vod_play_from",
                "roadEpisodesPath": "$.list[0].vod_play_url"
              }
            }
            """.trimIndent()
        )

        val (roads, diagnostics) = strategy.parseChapters(
            fixture("api_chapters_delimited.json"),
            rule.chapterApiConfig,
            source = "101",
            baseUrl = rule.baseUrl,
        )

        assertEquals(2, roads.size)
        assertEquals(listOf("线路A", "线路B"), roads.map { it.name })
        assertEquals(
            listOf("https://cdn.example.com/1.m3u8", "https://cdn.example.com/2.m3u8"),
            roads[0].urls,
        )
        assertEquals(listOf("第01集", "第02集"), roads[0].names)
        assertEquals(listOf("https://cdn.example.com/x.m3u8"), roads[1].urls)
        assertTrue(diagnostics.isEmpty(), diagnostics.toString())
    }

    @Test
    fun `validator rejects jsonpath features outside the restricted subset`() {
        val broken = searchRule.searchApiConfig.copy(listPath = "$.list[?(@.enabled)]")
        assertThrows(RuleFormatException::class.java) {
            strategy.validateSearchConfig(broken)
        }
    }

    @Test
    fun `source template without placeholder is rejected`() {
        val broken = searchRule.searchApiConfig.copy(sourceTemplate = "https://site.example/detail")
        assertThrows(RuleFormatException::class.java) {
            strategy.validateSearchConfig(broken)
        }
    }
}
