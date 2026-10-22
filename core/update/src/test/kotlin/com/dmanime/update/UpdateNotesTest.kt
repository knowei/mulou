package com.dmanime.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UpdateNotesTest {

    @Test
    fun `markdown 链接只留文字`() {
        val md = "- [KT-89267](https://youtrack.jetbrains.com/issue/KT-89267) 修好了"
        assertEquals("- KT-89267 修好了", UpdateNotes.plain(md))
    }

    @Test
    fun `标题井号去掉`() {
        val md = "## Changelog\n### JVM\n- 第一条"
        assertEquals("Changelog\nJVM\n- 第一条", UpdateNotes.plain(md))
    }

    @Test
    fun `强调星号去掉`() {
        assertEquals("重要：别升级", UpdateNotes.plain("**重要**：别升级"))
        assertEquals("重要：别升级", UpdateNotes.plain("__重要__：别升级"))
    }

    @Test
    fun `连续空行压成一行`() {
        assertEquals("a\n\nb", UpdateNotes.plain("a\n\n\n\nb  "))
        // 行首缩进是嵌套列表的语义，不能动
        assertEquals("a\n\n b", UpdateNotes.plain("a\n\n\n\n b"))
    }

    @Test
    fun `纯文本原样返回`() {
        assertEquals("第一行\n第二行", UpdateNotes.plain("第一行\n第二行"))
    }

    @Test
    fun `空正文不炸`() {
        assertEquals("", UpdateNotes.plain(""))
    }
}
