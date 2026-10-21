package com.dmanime.rules.css1

import com.dmanime.rules.engine.RuleValidator
import com.dmanime.rules.model.AnimeRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Css1ConverterTest {

    private val fixture = """
        {
          "exportedMediaSourceDataList": {
            "mediaSources": [
              {
                "factoryId": "web-selector",
                "arguments": {
                  "name": "甲站",
                  "tier": 3,
                  "searchConfig": {
                    "searchUrl": "https://a.example/vod/search.html?wd={keyword}",
                    "subjectFormatId": "a",
                    "selectorSubjectFormatA": {
                      "selectLists": ".module-card-item > .module-card-item-title > a"
                    },
                    "channelFormatId": "index-grouped",
                    "selectorChannelFormatFlattened": {
                      "selectChannelNames": ".module-tab-item > span",
                      "selectEpisodeLists": ".module-play-list-content",
                      "selectEpisodesFromList": "a"
                    }
                  }
                }
              },
              {
                "factoryId": "web-selector",
                "arguments": {
                  "name": "乙站",
                  "searchConfig": {
                    "searchUrl": "https://b.example/search?wd={keyword}",
                    "subjectFormatId": "indexed",
                    "selectorSubjectFormatIndexed": {
                      "selectNames": ".detail-info .title",
                      "selectLinks": ".detail-info > a"
                    },
                    "channelFormatId": "no-channel",
                    "selectorChannelFormatNoChannel": {
                      "selectEpisodes": ".anthology-list-play a"
                    }
                  }
                }
              },
              {
                "factoryId": "web-selector",
                "arguments": {
                  "name": "甲站",
                  "searchConfig": {
                    "searchUrl": "https://c.example/search?wd={keyword}",
                    "subjectFormatId": "a",
                    "selectorSubjectFormatA": { "selectLists": "a.item" },
                    "channelFormatId": "index-grouped",
                    "selectorChannelFormatFlattened": {
                      "selectEpisodeLists": "ul.clear",
                      "selectEpisodesFromList": "a span",
                      "selectEpisodeLinksFromList": "a"
                    }
                  }
                }
              },
              {
                "factoryId": "web-selector",
                "arguments": {
                  "name": "坏站",
                  "searchConfig": {
                    "searchUrl": "https://d.example/search",
                    "subjectFormatId": "a",
                    "selectorSubjectFormatA": { "selectLists": "a.item" },
                    "channelFormatId": "index-grouped",
                    "selectorChannelFormatFlattened": {
                      "selectEpisodeLists": "ul.clear",
                      "selectEpisodesFromList": "a"
                    }
                  }
                }
              }
            ]
          }
        }
    """.trimIndent()

    private fun convert(): Css1Converter.Outcome = Css1Converter.convert(fixture)

    @Test
    fun `converts css1 sources and skips broken ones`() {
        val outcome = convert()

        assertEquals(3, outcome.rules.size)
        assertEquals(1, outcome.skipped.size)
        assertTrue(outcome.skipped.single().contains("keyword"), outcome.skipped.toString())
    }

    @Test
    fun `anchor subject format uses the node itself`() {
        val rule = convert().rules.first()

        assertEquals("https://a.example/", rule.baseUrl)
        assertEquals("https://a.example/vod/search.html?wd=@keyword", rule.searchUrl)
        assertEquals(".", rule.searchName)
        assertEquals(".", rule.searchResult)
        assertTrue(rule.searchList.endsWith("/a"), rule.searchList)
    }

    @Test
    fun `index grouped channels keep their display names`() {
        val rule = convert().rules.first()

        assertEquals(".//a", rule.chapterResult)
        assertTrue(rule.chapterRoads.startsWith("//"), rule.chapterRoads)
        assertTrue(rule.roadNameSelector.startsWith("//"), rule.roadNameSelector)
    }

    @Test
    fun `indexed subject format aligns a separate name list`() {
        val rule = convert().rules[1]

        assertTrue(rule.searchNameSelector.startsWith("//"), rule.searchNameSelector)
        assertTrue(rule.searchList.contains("detail-info"), rule.searchList)
        assertEquals("", rule.chapterRoads, "no-channel 的 chapterRoads 必须为空")
        assertEquals(".//*[contains(concat(' ', normalize-space(@class), ' '), ' anthology-list-play ')]//a", rule.chapterResult)
    }

    @Test
    fun `separate episode link list is used as the chapter result`() {
        val rule = convert().rules[2]

        assertTrue(rule.searchList.contains("' item '"), rule.searchList)
        assertEquals(".//a", rule.chapterResult)
        assertTrue(rule.chapterRoads.contains("ul"), rule.chapterRoads)
        assertTrue(convert().notes.any { it.contains("两组节点") }, convert().notes.toString())
    }

    @Test
    fun `duplicate names are suffixed because rules are keyed by name`() {
        assertEquals(listOf("甲站", "乙站", "甲站（2）"), convert().rules.map(AnimeRule::name))
    }

    @Test
    fun `every converted rule passes static validation`() {
        val problems = convert().rules.associate { it.name to RuleValidator.validate(it) }
        problems.forEach { (name, issues) ->
            assertTrue(issues.isEmpty(), "$name: $issues")
        }
    }
}
