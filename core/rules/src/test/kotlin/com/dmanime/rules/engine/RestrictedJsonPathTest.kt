package com.dmanime.rules.engine

import com.dmanime.rules.error.RuleFormatException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RestrictedJsonPathTest {

    private val doc = Json.parseToJsonElement(
        """
        {
          "data": {
            "slug": "abc",
            "list": [{"id": 1, "name": "A"}, {"id": 2, "name": "B"}],
            "play-sources": {"x": 1}
          }
        }
        """.trimIndent()
    )

    @Test
    fun `accepts the supported subset`() {
        listOf(
            "$",
            "$.data",
            "$.data.slug",
            "$.data.list[*]",
            "$.data.list[0]",
            "$['data']",
            "$.data['play-sources']",
            "$.data.list[1].name",
        ).forEach { RestrictedJsonPath.validate(it) }
    }

    @Test
    fun `rejects everything outside the subset`() {
        listOf(
            "data",
            "$..name",
            "$.data.list[?(@.id>1)]",
            "$.data.list[0:2]",
            "$.data.list.length()",
            "$.",
            "$[]",
        ).forEach { expression ->
            assertThrows(RuleFormatException::class.java, { RestrictedJsonPath.validate(expression) }, expression)
        }
    }

    @Test
    fun `reads wildcard and index`() {
        val all = RestrictedJsonPath.read(doc, "$.data.list[*]")
        assertEquals(2, all.size)
        assertEquals("A", (all[0] as kotlinx.serialization.json.JsonObject)["name"].let { (it as JsonPrimitive).content })

        val second = RestrictedJsonPath.readFirst(doc, "$.data.list[1].id")
        assertEquals("2", (second as JsonPrimitive).content)
    }

    @Test
    fun `reads quoted field names containing dashes`() {
        val value = RestrictedJsonPath.readFirst(doc, "$['data']['play-sources'].x")
        assertEquals("1", (value as JsonPrimitive).content)
    }

    @Test
    fun `missing path yields null instead of throwing`() {
        assertNull(RestrictedJsonPath.readFirst(doc, "$.data.missing"))
        assertNull(RestrictedJsonPath.readFirst(doc, "$.data.list[9]"))
    }
}
