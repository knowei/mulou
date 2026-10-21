package com.dmanime.rules.engine

import com.dmanime.rules.model.parseRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XPathStrategyTest {

    private val strategy = XPathStrategy()

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText()

    @Test
    fun `search extracts name and href with class based selectors`() {
        val rule = parseRule(
            """
            {
              "name": "class-based",
              "baseURL": "https://site.example/",
              "searchURL": "https://site.example/search?wd=@keyword",
              "searchList": "//ul[contains(@class, 'hl-one-list')]/li[contains(@class, 'hl-list-item')]",
              "searchName": ".//div[contains(@class, 'hl-item-title')]/a",
              "searchResult": ".//div[contains(@class, 'hl-item-title')]/a",
              "chapterRoads": "//div[contains(@class,'hl-play-source')]//ul[contains(@class,'hl-plays-list')]",
              "chapterResult": ".//a[contains(@href,'/play/')]"
            }
            """.trimIndent()
        )

        val (items, diagnostics) = strategy.parseSearch(fixture("search.html"), rule)

        assertEquals(2, items.size)
        assertEquals("葬送的芙莉莲", items[0].name)
        assertEquals("/detail/1", items[0].src)
        assertEquals("迷宫饭", items[1].name)
        assertEquals("/detail/2", items[1].src)
        assertTrue(diagnostics.isEmpty(), diagnostics.toString())
    }

    /**
     * 真实规则里 74/86 条把条目字段写成 `//h3/a`。若按 XPath 1.0 的字面语义
     * （从文档根开始），每个条目都会拿到同一个节点；这里验证我们做了相对化。
     */
    @Test
    fun `absolute looking item selectors stay scoped to the item node`() {
        val rule = parseRule(
            """
            {
              "name": "absolute-selectors",
              "baseURL": "https://site.example/",
              "searchURL": "https://site.example/search?wd=@keyword",
              "searchList": "//div[@class='item']",
              "searchName": "//h3/a",
              "searchResult": "//h3/a",
              "chapterRoads": "//div",
              "chapterResult": "//a"
            }
            """.trimIndent()
        )

        val (items, _) = strategy.parseSearch(fixture("search_absolute.html"), rule)

        assertEquals(listOf("作品A", "作品B"), items.map { it.name })
        assertEquals(listOf("/a", "/b"), items.map { it.src })
    }

    @Test
    fun `chapters build roads and normalize relative and absolute urls`() {
        val rule = parseRule(
            """
            {
              "name": "chapters",
              "baseURL": "https://site.example/",
              "searchURL": "https://site.example/search?wd=@keyword",
              "searchList": "//ul/li",
              "searchName": "//a",
              "searchResult": "//a",
              "chapterRoads": "//div[contains(@class,'hl-play-source')]//ul[contains(@class,'hl-plays-list')]",
              "chapterResult": ".//a"
            }
            """.trimIndent()
        )

        val (roads, diagnostics) = strategy.parseChapters(fixture("detail.html"), rule)

        assertEquals(2, roads.size)
        assertEquals("播放线路1", roads[0].name)
        assertEquals(
            listOf("https://site.example/play/1-1", "https://site.example/play/1-2"),
            roads[0].urls,
        )
        // 剧集名会去掉所有空白，与 Kazumi 一致
        assertEquals(listOf("第1集", "第2集"), roads[0].names)
        assertEquals(listOf("https://cdn.example.com/media/2-1.m3u8"), roads[1].urls)
        assertTrue(diagnostics.isEmpty(), diagnostics.toString())
    }

    @Test
    fun `text node selectors are usable as item names`() {
        val rule = parseRule(
            """
            {
              "name": "text-node",
              "baseURL": "https://site.example/",
              "searchURL": "https://site.example/search?wd=@keyword",
              "searchList": "//li",
              "searchName": "//a/text()",
              "searchResult": "//a",
              "chapterRoads": "//ul",
              "chapterResult": "//li/a"
            }
            """.trimIndent()
        )

        val (items, _) = strategy.parseSearch(fixture("search.html"), rule)
        assertEquals(listOf("葬送的芙莉莲", "迷宫饭"), items.map { it.name })
    }

    /**
     * CSS1 的 indexed 格式：标题和链接是两组平行节点，按索引对齐。
     * 动画封面链接本身没有文本，名称必须来自另一组节点。
     */
    @Test
    fun `search name can come from a parallel list`() {
        val html = """
            <html><body>
              <ul class="posters">
                <li><a href="/d/1"><img src="a.jpg"></a></li>
                <li><a href="/d/2"><img src="b.jpg"></a></li>
              </ul>
              <div class="titles"><span>作品A</span><span>作品B</span></div>
            </body></html>
        """.trimIndent()
        val rule = parseRule(
            """
            {
              "name": "aligned",
              "baseURL": "https://site.example/",
              "searchURL": "https://site.example/search?wd=@keyword",
              "searchList": "//ul[@class='posters']/li/a",
              "searchName": ".",
              "searchResult": ".",
              "x-searchNameSelector": "//div[@class='titles']/span",
              "chapterRoads": "//div",
              "chapterResult": ".//a"
            }
            """.trimIndent()
        )

        val (items, diagnostics) = strategy.parseSearch(html, rule)

        assertEquals(listOf("作品A", "作品B"), items.map { it.name })
        assertEquals(listOf("/d/1", "/d/2"), items.map { it.src })
        assertTrue(diagnostics.isEmpty(), diagnostics.toString())
    }

    @Test
    fun `road names can come from a parallel list`() {
        val html = """
            <html><body>
              <div class="tabs"><span>线路A</span><span>线路B</span></div>
              <div class="lists"><ul><li><a href="/p/1-1">第1集</a></li></ul></div>
              <div class="lists"><ul><li><a href="/p/2-1">第1集</a></li></ul></div>
            </body></html>
        """.trimIndent()
        val rule = parseRule(
            """
            {
              "name": "named-roads",
              "baseURL": "https://site.example/",
              "searchURL": "https://site.example/search?wd=@keyword",
              "searchList": "//ul/li",
              "searchName": "//a",
              "searchResult": "//a",
              "chapterRoads": "//div[@class='lists']",
              "chapterResult": ".//a",
              "x-roadNameSelector": "//div[@class='tabs']/span"
            }
            """.trimIndent()
        )

        val (roads, diagnostics) = strategy.parseChapters(html, rule)

        assertEquals(listOf("线路A", "线路B"), roads.map { it.name })
        assertEquals(listOf("https://site.example/p/1-1"), roads[0].urls)
        assertTrue(diagnostics.isEmpty(), diagnostics.toString())
    }

    /** CSS1 的 no-channel 格式：整页只有一条线路，没有线路容器节点。 */
    @Test
    fun `blank chapterRoads treats the whole page as one road`() {
        val html = """
            <html><body>
              <div class="eps"><a href="/p/1">第1集</a><a href="/p/2">第2集</a></div>
            </body></html>
        """.trimIndent()
        val rule = parseRule(
            """
            {
              "name": "no-channel",
              "baseURL": "https://site.example/",
              "searchURL": "https://site.example/search?wd=@keyword",
              "searchList": "//ul/li",
              "searchName": "//a",
              "searchResult": "//a",
              "chapterRoads": "",
              "chapterResult": ".//a"
            }
            """.trimIndent()
        )

        val (roads, _) = strategy.parseChapters(html, rule)

        assertEquals(1, roads.size)
        assertEquals("播放线路1", roads[0].name)
        assertEquals(2, roads[0].urls.size)
    }
}
