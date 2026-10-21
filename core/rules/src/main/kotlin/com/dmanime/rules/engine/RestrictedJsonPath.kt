package com.dmanime.rules.engine

import com.dmanime.rules.error.RuleFormatException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Kazumi 的「受限 JSONPath」子集：只支持
 * `$`、`$.field`、`[0]`、`[*]`、`['field']`。
 *
 * 刻意不支持递归 `..`、过滤 `[?()]`、切片 `[0:2]`、函数调用——
 * 规则作者用不上，解析器因此可以只有一百行、并且行为完全可预测。
 */
object RestrictedJsonPath {

    private sealed interface Seg {
        data class Field(val name: String) : Seg
        data class Index(val index: Int) : Seg
        data object Wildcard : Seg
    }

    fun validate(expression: String) {
        compile(expression)
    }

    fun read(root: JsonElement, expression: String): List<JsonElement> {
        val segments = compile(expression)
        var current: List<JsonElement> = listOf(root)
        for (seg in segments) {
            val next = ArrayList<JsonElement>()
            for (node in current) {
                when (seg) {
                    is Seg.Field -> (node as? JsonObject)?.get(seg.name)?.let(next::add)
                    is Seg.Index -> (node as? JsonArray)?.getOrNull(seg.index)?.let(next::add)
                    Seg.Wildcard -> when (node) {
                        is JsonArray -> next.addAll(node)
                        is JsonObject -> next.addAll(node.values)
                        else -> Unit
                    }
                }
            }
            current = next
        }
        return current
    }

    fun readFirst(root: JsonElement, expression: String): JsonElement? =
        read(root, expression).firstOrNull()

    private fun compile(expression: String): List<Seg> {
        if (expression.isEmpty() || !expression.startsWith("$")) {
            throw RuleFormatException("JSONPath 必须以 \$ 开头: $expression")
        }
        val segments = ArrayList<Seg>()
        var i = 1
        while (i < expression.length) {
            when (val c = expression[i]) {
                '.' -> {
                    i++
                    val start = i
                    while (i < expression.length && isFieldChar(expression[i])) i++
                    if (i == start) throw RuleFormatException("不支持的 JSONPath: $expression")
                    segments += Seg.Field(expression.substring(start, i))
                }

                '[' -> {
                    val end = findBracketEnd(expression, i)
                    val content = expression.substring(i + 1, end).trim()
                    segments += when {
                        content == "*" -> Seg.Wildcard
                        content.isNotEmpty() && content.all { it.isDigit() } -> Seg.Index(content.toInt())
                        isQuoted(content) -> Seg.Field(unquote(content))
                        else -> throw RuleFormatException("不支持的 JSONPath 片段: [$content]")
                    }
                    i = end + 1
                }

                else -> throw RuleFormatException("不支持的 JSONPath: $expression（位置 $i 的 '$c'）")
            }
        }
        return segments
    }

    private fun isFieldChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '$' || c == '-'

    private fun isQuoted(s: String): Boolean =
        s.length >= 2 && ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith("\"") && s.endsWith("\"")))

    private fun unquote(s: String): String {
        val inner = s.substring(1, s.length - 1)
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                sb.append(inner[i + 1]); i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    private fun findBracketEnd(expression: String, start: Int): Int {
        var quote: Char? = null
        var escaped = false
        for (i in start + 1 until expression.length) {
            val c = expression[i]
            if (escaped) { escaped = false; continue }
            if (c == '\\') { escaped = true; continue }
            if (quote != null) { if (c == quote) quote = null; continue }
            if (c == '\'' || c == '"') { quote = c; continue }
            if (c == ']') return i
        }
        throw RuleFormatException("JSONPath 缺少 ]: $expression")
    }
}
