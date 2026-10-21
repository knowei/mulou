package com.dmanime.danmaku

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BiliWbiTest {

    private val imgKey = "7cd084941338484aae1ad9425b84077c"
    private val subKey = "4932caff0ff746eab6f01bf08b70ac45"

    @Test
    fun `mixinKey 是 32 位且与社区验证值一致`() {
        val key = BiliWbi.mixinKey(imgKey, subKey)
        assertEquals(32, key.length)
        assertEquals("ea1db124af3c7062474693fa704f4ff8", key)
    }

    @Test
    fun `同样输入签名稳定`() {
        val params = mapOf("search_type" to "media_bangumi", "keyword" to "芙莉莲", "page" to "1")
        val key = BiliWbi.mixinKey(imgKey, subKey)
        val a = BiliWbi.sign(params, 1700000000L, key)
        val b = BiliWbi.sign(params, 1700000000L, key)
        assertEquals(a["w_rid"], b["w_rid"])
        assertEquals("1700000000", a["wts"])
    }

    @Test
    fun `时间戳变了签名也变`() {
        val params = mapOf("keyword" to "芙莉莲")
        val key = BiliWbi.mixinKey(imgKey, subKey)
        val a = BiliWbi.sign(params, 1700000000L, key)
        val b = BiliWbi.sign(params, 1700000001L, key)
        assertNotEquals(a["w_rid"], b["w_rid"])
    }

    @Test
    fun `过滤特殊字符后再签名`() {
        val key = BiliWbi.mixinKey(imgKey, subKey)
        val plain = BiliWbi.sign(mapOf("keyword" to "ab"), 1700000000L, key)
        val messy = BiliWbi.sign(mapOf("keyword" to "a'b!(c)*"), 1700000000L, key)
        // 特殊字符被剔除后 "a'b!(c)*" 等价于 "abc"，与 "ab" 不同但与 "abc" 相同
        val abc = BiliWbi.sign(mapOf("keyword" to "abc"), 1700000000L, key)
        assertNotEquals(plain["w_rid"], messy["w_rid"])
        assertEquals(abc["w_rid"], messy["w_rid"])
    }

    @Test
    fun `参数顺序不影响签名`() {
        val key = BiliWbi.mixinKey(imgKey, subKey)
        val a = BiliWbi.sign(linkedMapOf("a" to "1", "b" to "2"), 1700000000L, key)
        val b = BiliWbi.sign(linkedMapOf("b" to "2", "a" to "1"), 1700000000L, key)
        assertEquals(a["w_rid"], b["w_rid"])
        assertTrue((a["w_rid"] ?: "").length == 32)
    }
}
