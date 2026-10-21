package com.dmanime.rules.css1

import com.dmanime.rules.error.RuleFormatException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * CSS1 规则包里实际出现的选择器写法（108 条）都能落到这里覆盖的子集。
 * 断言直接比对 XPath 文本：翻译层的输出会被 `javax.xml.xpath` 原样执行，
 * 没有二次加工，所以文本就是行为。
 */
class CssSelectorTest {

    private val cls = { name: String ->
        "[contains(concat(' ', normalize-space(@class), ' '), ' $name ')]"
    }

    @Test
    fun `class and child combinator`() {
        assertEquals(
            "//*${cls("module-card-item")}/*${cls("module-card-item-title")}/a",
            CssSelector.toXPath(".module-card-item > .module-card-item-title > a"),
        )
    }

    @Test
    fun `tag with class and descendant`() {
        assertEquals(
            "//div${cls("play_source_tab")}//a[@href='javascript:void(0);']",
            CssSelector.toXPath("div.play_source_tab a[href=\"javascript:void(0);\"]"),
        )
    }

    @Test
    fun `id and nth-child keep position semantics`() {
        // dd:nth-child(2) 是「第 2 个子元素且是 dd」，不是「第 2 个 dd」。
        assertEquals(
            "//*[@id='cont_pub']/dl/*[position()=2][self::dd]/div/a",
            CssSelector.toXPath("#cont_pub > dl> dd:nth-child(2) > div > a"),
        )
    }

    @Test
    fun `attribute operators`() {
        assertEquals(
            "//dl//h3//a[starts-with(@href, '/anime/')]",
            CssSelector.toXPath("dl h3 a[href^='/anime/']"),
        )
        assertEquals(
            "//a[contains(@href, 'play')]",
            CssSelector.toXPath("a[href*='play']"),
        )
        assertEquals(
            "//a[substring(@href, string-length(@href) - 5 + 1) = '.html']",
            CssSelector.toXPath("a[href$='.html']"),
        )
        assertEquals(
            "//a[@target]",
            CssSelector.toXPath("a[target]"),
        )
    }

    @Test
    fun `comma becomes union`() {
        assertEquals(
            "//ul${cls("list")} | //div${cls("list")}",
            CssSelector.toXPath("ul.list, div.list"),
        )
    }

    @Test
    fun `relative mode scopes to the context node`() {
        assertEquals(
            ".//*${cls("module-play-list-content")}//a",
            CssSelector.toXPath(".module-play-list-content a", relative = true),
        )
        assertEquals(
            ".//a${cls("episode")}",
            CssSelector.toXPath("a.episode", relative = true),
        )
    }

    @Test
    fun `unsupported pseudo classes fail loudly`() {
        assertThrows(RuleFormatException::class.java) {
            CssSelector.toXPath("div:has(a)")
        }
        assertThrows(RuleFormatException::class.java) {
            CssSelector.toXPath("li:nth-child(2n+1)")
        }
    }
}
