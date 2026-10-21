package com.dmanime.rules.css1

import com.dmanime.rules.error.RuleFormatException

/**
 * CSS 选择器 → XPath 1.0 的翻译层。
 *
 * 为什么要翻译而不是在运行时跑 CSS：core 的抓取链路只有 `javax.xml.xpath`
 * 一个执行器，运行时再加一套 CSS 引擎意味着两套求值语义、两套校验、两套报错。
 * CSS1 规则包里的 108 条选择器全部落在下面的子集内（tag / class / id /
 * 属性 / 后代 / 子元素 / 并集 / `:nth-child`），翻译成 XPath 后规则就是
 * 「原生规则」，可以导出给 Kazumi，也不需要长期维护第二个执行器。
 *
 * 明确不支持（遇到会抛错，由调用方记成"跳过原因"，不静默降级）：
 * `:has()`、`:contains()`、`:not()`、`:nth-of-type()`、属性选择器的 `i` 标志、
 * `an+b` 形式的 `:nth-child()`。
 */
object CssSelector {

    /** @param relative true 时以 `.//` 起头，用于「条目节点内部的相对查找」。 */
    fun toXPath(css: String, relative: Boolean = false): String {
        val parts = splitTopLevel(css, ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw RuleFormatException("CSS 选择器不能为空")
        return parts.joinToString(" | ") { translatePart(it, relative) }
    }

    // ---------------------------------------------------------------- 结构

    private fun translatePart(part: String, relative: Boolean): String {
        val tokens = tokenize(part)
        val compounds = ArrayList<String>()
        val combinators = ArrayList<Char>()
        var next: Char = ' '

        for (token in tokens) {
            if (token == ">") {
                next = '>'
                continue
            }
            if (compounds.isNotEmpty()) combinators += next
            compounds += translateCompound(token)
            next = ' '
        }
        if (compounds.isEmpty()) throw RuleFormatException("CSS 选择器无法解析: $part")

        return buildString {
            append(if (relative) ".//" else "//")
            append(compounds.first())
            for (index in 1 until compounds.size) {
                append(if (combinators[index - 1] == '>') "/" else "//")
                append(compounds[index])
            }
        }
    }

    /** 按顶层空白与 `>` 切成「复合选择器」和子元素标记。 */
    private fun tokenize(part: String): List<String> {
        val tokens = ArrayList<String>()
        val buffer = StringBuilder()
        var depth = 0
        for (c in part) {
            when {
                c == '[' || c == '(' -> {
                    depth++
                    buffer.append(c)
                }

                c == ']' || c == ')' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    buffer.append(c)
                }

                depth == 0 && c == '>' -> {
                    if (buffer.isNotBlank()) {
                        tokens += buffer.toString().trim()
                        buffer.clear()
                    }
                    tokens += ">"
                }

                depth == 0 && c.isWhitespace() -> {
                    if (buffer.isNotBlank()) {
                        tokens += buffer.toString().trim()
                        buffer.clear()
                    }
                }

                else -> buffer.append(c)
            }
        }
        if (buffer.isNotBlank()) tokens += buffer.toString().trim()
        return tokens
    }

    // ---------------------------------------------------------------- 复合选择器

    private val TAG = Regex("^[A-Za-z][A-Za-z0-9-]*")
    private val ATTR = Regex("""^([A-Za-z_:][-A-Za-z0-9_:.]*)\s*(?:([~^$*|]?=)\s*(.+))?$""")

    private fun translateCompound(raw: String): String {
        var index = 0
        var tag: String? = null
        if (raw.startsWith("*")) {
            index = 1
        } else {
            val match = TAG.find(raw)
            if (match != null) {
                tag = match.value.lowercase()
                index = match.value.length
            }
        }

        val predicates = ArrayList<String>()
        var nth: String? = null

        while (index < raw.length) {
            when (val c = raw[index]) {
                '.' -> {
                    index++
                    val name = readIdentifier(raw, index)
                    if (name.isEmpty()) throw RuleFormatException("CSS 类名缺失: $raw")
                    index += name.length
                    predicates += classPredicate(name)
                }

                '#' -> {
                    index++
                    val name = readIdentifier(raw, index)
                    if (name.isEmpty()) throw RuleFormatException("CSS id 缺失: $raw")
                    index += name.length
                    predicates += "[@id=${literal(name)}]"
                }

                '[' -> {
                    val end = closingIndex(raw, index, '[', ']')
                    predicates += attributePredicate(raw.substring(index + 1, end))
                    index = end + 1
                }

                ':' -> {
                    val (predicate, end) = pseudoPredicate(raw, index)
                    nth = predicate
                    index = end
                }

                else -> throw RuleFormatException("不支持的 CSS 片段「$c」: $raw")
            }
        }

        // 位置谓词必须先于标签判断：`dd:nth-child(2)` 是「第 2 个子元素且是 dd」，
        // 若写成 `dd[position()=2]` 就变成「第 2 个 dd」了。
        val ordered = ArrayList<String>()
        val base: String
        if (nth != null) {
            base = "*"
            ordered += nth!!
            if (tag != null) ordered += "[self::$tag]"
        } else {
            base = tag ?: "*"
        }
        ordered += predicates
        return base + ordered.joinToString("")
    }

    private fun readIdentifier(text: String, start: Int): String {
        var end = start
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '-' || text[end] == '_')) {
            end++
        }
        return text.substring(start, end)
    }

    private fun closingIndex(text: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var quote: Char? = null
        for (index in start until text.length) {
            val c = text[index]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        throw RuleFormatException("CSS 选择器缺少 $close: $text")
    }

    // ---------------------------------------------------------------- 谓词

    private fun classPredicate(name: String): String =
        "[contains(concat(' ', normalize-space(@class), ' '), ${literal(" $name ")})]"

    private fun attributePredicate(body: String): String {
        val match = ATTR.matchEntire(body.trim())
            ?: throw RuleFormatException("不支持的属性选择器: [$body]")
        val name = match.groupValues[1]
        val operator = match.groupValues[2]
        val rawValue = match.groupValues[3].trim()
        if (operator.isEmpty() || rawValue.isEmpty()) return "[@$name]"

        val value = unquote(rawValue)
        return when (operator) {
            "=" -> "[@$name=${literal(value)}]"
            "^=" -> "[starts-with(@$name, ${literal(value)})]"
            "*=" -> "[contains(@$name, ${literal(value)})]"
            "$=" -> "[substring(@$name, string-length(@$name) - ${value.length} + 1) = ${literal(value)}]"
            "~=" -> "[contains(concat(' ', normalize-space(@$name), ' '), ${literal(" $value ")})]"
            "|=" -> "[@$name=${literal(value)} or starts-with(@$name, ${literal("$value-")})]"
            else -> throw RuleFormatException("不支持的属性运算符 $operator")
        }
    }

    private fun pseudoPredicate(raw: String, start: Int): Pair<String, Int> {
        var index = start + 1
        val name = readIdentifier(raw, index).lowercase()
        if (name.isEmpty()) throw RuleFormatException("CSS 伪类缺失: $raw")
        index += name.length

        var argument = ""
        if (index < raw.length && raw[index] == '(') {
            val end = closingIndex(raw, index, '(', ')')
            argument = raw.substring(index + 1, end).trim()
            index = end + 1
        }

        val predicate = when (name) {
            "first-child" -> "[position()=1]"
            "last-child" -> "[position()=last()]"
            "nth-child" -> nthChild(argument)
            else -> throw RuleFormatException("不支持的 CSS 伪类 :$name")
        }
        return predicate to index
    }

    private fun nthChild(argument: String): String {
        val text = argument.lowercase().replace(" ", "")
        return when {
            text == "odd" -> "[position() mod 2 = 1]"
            text == "even" -> "[position() mod 2 = 0]"
            text.toIntOrNull() != null -> {
                val value = text.toInt()
                if (value < 1) throw RuleFormatException(":nth-child($argument) 必须从 1 开始")
                "[position()=$value]"
            }

            else -> throw RuleFormatException("不支持的 :nth-child($argument)")
        }
    }

    // ---------------------------------------------------------------- 字面量

    private fun unquote(value: String): String {
        if (value.length >= 2) {
            val first = value.first()
            if ((first == '\'' || first == '"') && value.last() == first) {
                return value.substring(1, value.length - 1)
            }
        }
        return value
    }

    private fun literal(value: String): String = when {
        !value.contains('\'') -> "'$value'"
        !value.contains('"') -> "\"$value\""
        else -> "concat(" + value.split("'").joinToString(", \"'\", ") { "'$it'" } + ")"
    }

    private fun splitTopLevel(text: String, delimiter: Char): List<String> {
        val parts = ArrayList<String>()
        val buffer = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (c in text) {
            when {
                quote != null -> {
                    buffer.append(c)
                    if (c == quote) quote = null
                }

                c == '\'' || c == '"' -> {
                    quote = c
                    buffer.append(c)
                }

                c == '[' || c == '(' -> {
                    depth++
                    buffer.append(c)
                }

                c == ']' || c == ')' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    buffer.append(c)
                }

                depth == 0 && c == delimiter -> {
                    parts += buffer.toString()
                    buffer.clear()
                }

                else -> buffer.append(c)
            }
        }
        parts += buffer.toString()
        return parts
    }
}
