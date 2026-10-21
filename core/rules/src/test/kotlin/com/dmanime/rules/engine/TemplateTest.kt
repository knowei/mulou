package com.dmanime.rules.engine

import com.dmanime.rules.error.RuleFormatException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TemplateTest {

    @Test
    fun `encodes values when asked`() {
        val out = Template.render("/search?wd=@keyword", mapOf("keyword" to "进击 的巨人"), encode = true)
        assertEquals("/search?wd=%E8%BF%9B%E5%87%BB%20%E7%9A%84%E5%B7%A8%E4%BA%BA", out)
    }

    @Test
    fun `does not touch at signs glued to word characters`() {
        val out = Template.render("mail a@b.com and @source", mapOf("source" to "X"))
        assertEquals("mail a@b.com and X", out)
    }

    /** 详情页模板走朴素替换，`GV@source` 也必须生效。 */
    @Test
    fun `source template replaces even when glued to letters`() {
        assertEquals(
            "https://x.example/GV123/",
            Template.renderSourceTemplate("https://x.example/GV@source/", "123"),
        )
    }

    @Test
    fun `missing variable is an error not an empty string`() {
        assertThrows(RuleFormatException::class.java) {
            Template.render("/@page", emptyMap())
        }
    }

    @Test
    fun `exact variable in json body keeps its numeric type`() {
        val body = Json.parseToJsonElement("""{"p_id":"@source","page":1}""") as JsonObject
        val rendered = Template.renderValue(body, mapOf("source" to 101)) as JsonObject

        val pId = rendered["p_id"] as JsonPrimitive
        assertFalse(pId.isString, "数字变量不应退化成字符串")
        assertEquals("101", pId.content)
        assertEquals("1", (rendered["page"] as JsonPrimitive).content)
    }

    @Test
    fun `partial placeholder inside a longer string is rendered as text`() {
        val body = Json.parseToJsonElement("""{"q":"id=@source&v=2"}""") as JsonObject
        val rendered = Template.renderValue(body, mapOf("source" to 7)) as JsonObject
        assertEquals("id=7&v=2", (rendered["q"] as JsonPrimitive).content)
    }
}
