package com.dmanime.danmaku

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class BiliDanmakuTest {

    private val sample = """
        <i>
          <chatserver>chat.bilibili.com</chatserver>
          <d p="12.34,1,25,16777215,1699999999,0,abc,123">前面的别走</d>
          <d p="12.34,1,25,16777215,1699999999,0,abc,124">前面的别走</d>
          <d p="95.5,5,25,16711680,1699999999,0,def,125">顶部字幕</d>
          <d p="100.0,4,25,255,1699999999,0,ghi,126">底部字幕</d>
          <d p="110.0,7,25,16777215,1699999999,0,jkl,127">高级弹幕要丢掉</d>
          <d p="120.0,8,25,16777215,1699999999,0,mno,128">代码弹幕要丢掉</d>
          <d p="130.0,1,25,16777215,1699999999,0,pqr,129">a &amp; b &lt;c&gt;</d>
          <d p="bad,1,25,0,0,0,x,1">时间坏了</d>
        </i>
    """.trimIndent()

    @Test
    fun `解析出滚动与固定弹幕并丢弃特殊模式`() {
        val list = BiliDanmaku.parse(sample)

        assertEquals(4, list.size, "重复条目应合并，模式 7/8 与坏时间应丢弃")
        assertEquals(listOf(12340L, 95500L, 100000L, 130000L), list.map { it.timeMs })
        assertEquals(Danmaku.Mode.Scroll, list[0].mode)
        assertEquals(Danmaku.Mode.Top, list[1].mode)
        assertEquals(Danmaku.Mode.Bottom, list[2].mode)
    }

    @Test
    fun `颜色被补成不透明`() {
        val list = BiliDanmaku.parse(sample)
        assertEquals(0xFFFFFFFF.toInt(), list[0].color, "16777215 应变成不透明白")
        assertEquals(0xFFFF0000.toInt(), list[1].color)
    }

    @Test
    fun `实体被还原`() {
        val list = BiliDanmaku.parse(sample)
        assertEquals("a & b <c>", list.last().text)
    }

    @Test
    fun `超长文本被丢弃`() {
        val long = "字".repeat(200)
        val xml = """<i><d p="1.0,1,25,16777215,0,0,x,1">$long</d></i>"""
        assertTrue(BiliDanmaku.parse(xml).isEmpty())
    }

    @Test
    fun `raw deflate 能被解开`() {
        val xml = """<i><d p="1.0,1,25,16777215,0,0,x,1">压缩过的弹幕</d></i>"""
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true) // nowrap = true，与 B 站一致
        deflater.setInput(xml.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        deflater.end()

        val decoded = BiliDanmaku.decode(out.toByteArray())
        assertEquals(xml, decoded)
        assertEquals(1, BiliDanmaku.parse(decoded).size)
    }

    @Test
    fun `明文也能读`() {
        val plain = """<i><d p="2.0,5,25,255,0,0,x,1">没压缩</d></i>"""
        assertEquals(plain, BiliDanmaku.decode(plain.toByteArray(Charsets.UTF_8)))
    }
}
