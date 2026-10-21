package com.dmanime.rules.engine

import com.dmanime.rules.model.parseRule
import com.dmanime.rules.net.HttpResponse
import com.dmanime.rules.net.RuleHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RuleEngineTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText()

    /**
     * 端到端：API 搜索（带 sourceTemplate）+ delimited 章节。
     * 这条链路就是「TVBox type1 站点 → 原生规则」转换后的形态。
     */
    @Test
    fun `search then chapters end to end`() {
        val rule = parseRule(
            """
            {
              "name": "e2e",
              "baseURL": "https://site.example/",
              "searchMode": "api",
              "chapterMode": "api",
              "searchApiConfig": {
                "request": {"method": "GET", "url": "https://site.example/api/search", "query": {"wd": "@keyword"}},
                "listPath": "$.list[*]",
                "namePath": "$.name",
                "sourcePath": "$.id"
              },
              "chapterApiConfig": {
                "request": {"method": "GET", "url": "https://site.example/api/detail/@source"},
                "format": "delimited",
                "roadNamesPath": "$.list[0].vod_play_from",
                "roadEpisodesPath": "$.list[0].vod_play_url"
              }
            }
            """.trimIndent()
        )

        val client = RuleHttpClient { request ->
            when {
                request.url.contains("/api/search") -> HttpResponse(200, fixture("api_search.json"))
                request.url.contains("/api/detail") -> HttpResponse(200, fixture("api_chapters_delimited.json"))
                else -> HttpResponse(404, "")
            }
        }
        val engine = RuleEngine(client)

        val search = engine.search(rule, "芙莉莲")
        assertEquals(2, search.items.size)
        assertEquals("作品A", search.items[0].name)
        assertEquals("101", search.items[0].src)

        val chapters = engine.chapters(rule, search.items[0].src)
        assertEquals(2, chapters.roads.size)
        assertEquals(2, chapters.roads[0].urls.size)
        assertEquals("https://cdn.example.com/1.m3u8", chapters.roads[0].urls[0])
    }
}
