package com.dmanime.update

/**
 * Release 正文是 markdown，但界面上只有一个纯文本对话框。
 *
 * 原样显示会看到一堆 `###` 和 `[KT-1234](https://...)`，读起来很吵。
 * 这里只做最小限度的清理：链接留文字、去掉标题井号和强调星号。
 * 不追求完整的 markdown 渲染——真正的排版在发布页看。
 */
object UpdateNotes {

    private val LINK = Regex("\\[([^\\]]+)\\]\\(([^)]*)\\)")
    private val TITLE = Regex("(?m)^#{1,6}\\s*")
    private val EMPHASIS = Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__")
    private val BLANK_RUN = Regex("\n{3,}")

    fun plain(markdown: String): String {
        var text = markdown.replace("\r\n", "\n")
        text = LINK.replace(text) { it.groupValues[1] }
        text = TITLE.replace(text, "")
        text = EMPHASIS.replace(text) { m ->
            m.groups[1]?.value ?: m.groups[2]?.value ?: m.value
        }
        // markdown 里行尾常有两个空格表示换行，这里按纯文本处理，去掉
        text = text.lines().joinToString("\n") { it.trimEnd() }
        text = BLANK_RUN.replace(text, "\n\n")
        return text.trim()
    }
}
