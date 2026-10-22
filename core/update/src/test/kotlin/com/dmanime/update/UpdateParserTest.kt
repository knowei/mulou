package com.dmanime.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateParserTest {

    private fun release(
        tag: String,
        body: String = "",
        draft: Boolean = false,
        prerelease: Boolean = false,
        apk: String? = null,
    ): String {
        val assets = if (apk == null) "[]" else
            """[{"name":"komo-$tag.apk","browser_download_url":"$apk"}]"""
        return """
            {"tag_name":"$tag","body":"$body","html_url":"https://github.com/o/r/releases/tag/$tag",
             "draft":$draft,"prerelease":$prerelease,"assets":$assets}
        """.trimIndent()
    }

    @Test
    fun `取最新一条并去掉 v 前缀`() {
        val json = "[${release("v1.2")},${release("v1.1")}]"
        val got = UpdateParser.parseLatest(json)!!
        assertEquals("1.2", got.version)
        assertEquals("https://github.com/o/r/releases/tag/v1.2", got.pageUrl)
    }

    @Test
    fun `草稿和预发布都要跳过`() {
        val json = "[${release("v2.0", draft = true)},${release("v1.9", prerelease = true)},${release("v1.3")}]"
        assertEquals("1.3", UpdateParser.parseLatest(json)!!.version)
    }

    @Test
    fun `挂了 apk 就取直链`() {
        val json = "[${release("v1.2", apk = "https://x/y.apk")}]"
        assertEquals("https://x/y.apk", UpdateParser.parseLatest(json)!!.apkUrl)
    }

    @Test
    fun `没挂 apk 时直链为空`() {
        val json = "[${release("v1.2")}]"
        assertEquals("", UpdateParser.parseLatest(json)!!.apkUrl)
    }

    @Test
    fun `更新说明原样取出并去首尾空行`() {
        val json = """[{"tag_name":"v1.2","body":"  ## 新增\n- 检查更新  ","html_url":"u","assets":[]}]"""
        val got = UpdateParser.parseLatest(json)!!
        assertEquals("## 新增\n- 检查更新", got.notes)
        assertEquals("u", got.pageUrl)
    }

    @Test
    fun `没有发布记录返回 null`() {
        assertNull(UpdateParser.parseLatest("[]"))
    }

    @Test
    fun `返回体不是 JSON 时不炸`() {
        assertNull(UpdateParser.parseLatest("<html>404</html>"))
    }

    @Test
    fun `缺 tag_name 的条目跳过`() {
        val json = """[{"body":"x"},${release("v1.1")}]"""
        assertEquals("1.1", UpdateParser.parseLatest(json)!!.version)
    }
}

class AppVersionTest {

    @Test
    fun `按数字段比较而不是字符串`() {
        assertTrue(AppVersion.isNewer("1.10", "1.9"))
        assertFalse(AppVersion.isNewer("1.9", "1.10"))
    }

    @Test
    fun `位数不同的版本按缺省 0 处理`() {
        assertFalse(AppVersion.isNewer("1.1", "1.1.0"))
        assertTrue(AppVersion.isNewer("1.1.1", "1.1"))
        assertFalse(AppVersion.isNewer("1", "1.0"))
    }

    @Test
    fun `v 前缀不影响判断`() {
        assertTrue(AppVersion.isNewer("v2.0", "1.9"))
        assertFalse(AppVersion.isNewer("v1.0", "1.0"))
    }

    @Test
    fun `预发布后缀被忽略`() {
        assertEquals(listOf(1, 2), AppVersion.segments("v1.2-beta1"))
        assertFalse(AppVersion.isNewer("1.2-beta1", "1.2"))
    }

    @Test
    fun `同样的版本不算新版`() {
        assertFalse(AppVersion.isNewer("1.0", "1.0"))
    }
}
