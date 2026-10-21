package com.dmanime.rules.engine

import com.dmanime.rules.error.RuleFormatException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * `@变量` 模板。
 *
 * Kazumi 里有两套替换语义，必须分开实现，不能合并：
 *
 * - [render]：URL / body 模板。正则带负向后瞻，只替换「前面不是字母、数字、
 *   下划线」的 `@name`，避免误伤 `a@b.com` 这类文本。
 * - [renderSourceTemplate]：详情页模板。朴素替换所有 `@source`，
 *   所以 `https://x/GV@source/` 这种紧贴字母的写法才能生效。
 */
object Template {

    private val VAR_REGEX = Regex("(?<![A-Za-z0-9_])@([A-Za-z_][A-Za-z0-9_]*)")
    private val EXACT_REGEX = Regex("^@([A-Za-z_][A-Za-z0-9_]*)$")

    fun render(template: String, vars: Map<String, Any?>, encode: Boolean = false): String =
        VAR_REGEX.replace(template) { match ->
            val name = match.groupValues[1]
            if (!vars.containsKey(name)) throw RuleFormatException("缺少模板变量 @$name")
            val text = vars[name]?.toString() ?: ""
            if (encode) encodeComponent(text) else text
        }

    /** 详情页模板：朴素替换，`https://x/GV@source/` 也能用。 */
    fun renderSourceTemplate(template: String, source: String): String {
        if (template.isEmpty()) return source
        return template.replace("@source", encodeComponent(source))
    }

    fun renderValue(value: JsonElement, vars: Map<String, Any?>): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { renderValue(it.value, vars) })
        is JsonArray -> JsonArray(value.map { renderValue(it, vars) })
        is JsonPrimitive -> {
            if (value.isString) {
                val text = value.content
                val exact = EXACT_REGEX.matchEntire(text)
                if (exact != null) {
                    val name = exact.groupValues[1]
                    if (!vars.containsKey(name)) throw RuleFormatException("缺少模板变量 @$name")
                    toJsonElement(vars[name])
                } else {
                    JsonPrimitive(render(text, vars, encode = false))
                }
            } else {
                value
            }
        }
        else -> value
    }

    fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    /**
     * 注意：必须用 `encode(text, "UTF-8")` 这个老重载。
     * `encode(String, Charset)` 是 Java 10 / Android API 33 才有的，SDK 32 上会
     * NoSuchMethodError——单测跑在 JDK 21 上永远发现不了。
     */
    fun encodeComponent(text: String): String =
        URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
