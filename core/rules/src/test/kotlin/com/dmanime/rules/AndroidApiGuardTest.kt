package com.dmanime.rules

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 防「JVM 能跑、Android 炸」的 API 回归。
 *
 * 背景：`URLEncoder.encode(String, Charset)` 是 Java 10 / Android API 33 的
 * 重载。核心模块是纯 JVM 库，单测跑在 JDK 21 上一切正常，装到 Android 12
 * （API 32）设备上直接 NoSuchMethodError。这个测试用静态扫描兜住这类问题，
 * 比等用户点出来便宜得多。
 *
 * 真正的长期解法是把 core 模块改成 Android library，让 Lint 的 NewApi 检查
 * 覆盖它们；在那之前用这个测试顶着。
 */
class AndroidApiGuardTest {

    private val coreRoot = File(System.getProperty("repo.root") ?: ".", "core")

    private val forbidden = listOf(
        Regex("""URLEncoder\.encode\([^)]*,\s*StandardCharsets""") to
            "URLEncoder.encode(String, Charset) 需要 API 33，改用 encode(text, \"UTF-8\")",
        Regex("""URLDecoder\.decode\([^)]*,\s*StandardCharsets""") to
            "URLDecoder.decode(String, Charset) 需要 API 33，改用 decode(text, \"UTF-8\")",
        Regex("""\.readAllBytes\(\)""") to "InputStream.readAllBytes 需要 API 33",
        Regex("""\bList\.of\(""") to "List.of 是 Java 9，Android 上不可用",
        Regex("""\bMap\.of\(""") to "Map.of 是 Java 9，Android 上不可用",
        Regex("""\bSet\.of\(""") to "Set.of 是 Java 9，Android 上不可用",
        Regex("""\.stripIndent\(\)|\.stripLeading\(\)|\.stripTrailing\(\)""") to
            "String.strip* 需要 API 33",
    )

    @Test
    fun `core modules avoid java 9 plus apis`() {
        val sources = coreRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .toList()

        assertTrue(sources.isNotEmpty(), "没有扫到源码，路径可能不对: ${coreRoot.absolutePath}")

        val violations = mutableListOf<String>()
        for (file in sources) {
            val text = file.readText()
            for ((pattern, hint) in forbidden) {
                if (pattern.containsMatchIn(text)) {
                    violations += "${file.name}: $hint"
                }
            }
        }

        assertTrue(violations.isEmpty(), "发现 Android 不兼容的 API 用法:\n" + violations.joinToString("\n"))
    }
}
