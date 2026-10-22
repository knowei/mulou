package com.dmanime.danmaku

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BiliReplyTest {

    private fun parse(text: String): JsonObject =
        Json.parseToJsonElement(text) as JsonObject

    /** 结构照抄 `x/v2/reply/wbi/main` 的真实返回，字段只留用到的。 */
    private val page = """
        {"code":0,"message":"OK","data":{
          "cursor":{"next":2,"is_end":false,"all_count":2862,"mode":3},
          "replies":[
            {"rpid":266602525681,"like":3531,"ctime":1751709972,"rcount":217,
             "member":{"uname":"soyo-风","avatar":"https://i0.hdslb.com/bfs/face/a.jpg"},
             "content":{"message":"我以为第二季呢"},
             "reply_control":{"location":"IP属地：上海"},
             "replies":[
               {"rpid":266602525682,"like":0,"ctime":1751709999,"rcount":0,
                "member":{"uname":"无忻wf"},
                "content":{"message":"签个到"}},
               {"rpid":266602525683,"like":0,"ctime":1751710000,"rcount":0,
                "member":{"uname":"驰炎症"},
                "content":{"message":"你建不建啊"}},
               {"rpid":266602525684,"like":0,"ctime":1751710001,"rcount":0,
                "member":{"uname":"氤氲氘"},
                "content":{"message":"吓我一跳"}}
             ]},
            {"rpid":266602525690,"like":12,"ctime":1751711000,"rcount":0,
             "member":{"uname":"孤独摇滚"},
             "content":{"message":"  芙莉莲！  "}}
          ]}}
    """.trimIndent()

    @Test
    fun `解析第一页评论`() {
        val result = BiliReplyParser.parsePage(parse(page))
        assertEquals(2, result.replies.size)
        assertEquals(2862, result.total)
        assertEquals(2L, result.next)
        assertTrue(result.hasMore)

        val first = result.replies[0]
        assertEquals(266602525681L, first.rpid)
        assertEquals("soyo-风", first.user)
        assertEquals(3531, first.like)
        assertEquals(217, first.replyCount)
        assertEquals("我以为第二季呢", first.message)
        // IP 属地前缀要去掉，界面上只显示城市
        assertEquals("上海", first.location)
        assertEquals(3, first.preview.size)
        assertEquals("签个到", first.preview[0].message)
    }

    @Test
    fun `正文两端空白会裁掉`() {
        val result = BiliReplyParser.parsePage(parse(page))
        assertEquals("芙莉莲！", result.replies[1].message)
    }

    @Test
    fun `data 为 null 时给空页`() {
        val result = BiliReplyParser.parsePage(parse("""{"code":-404,"message":"啥都木有","data":null}"""))
        assertEquals(0, result.replies.size)
        assertFalse(result.hasMore)
        assertEquals(BiliReplyPage.EMPTY, result)
    }

    @Test
    fun `最后一页的 hasMore 是 false`() {
        val text = """
            {"code":0,"data":{"cursor":{"next":0,"is_end":true,"all_count":40},
             "replies":[{"rpid":1,"member":{"uname":"a"},"content":{"message":"1"}}]}}
        """.trimIndent()
        val result = BiliReplyParser.parsePage(parse(text))
        assertEquals(1, result.replies.size)
        assertFalse(result.hasMore)
        assertEquals(0L, result.next)
    }

    @Test
    fun `没有游标时按最后一页处理`() {
        val text = """
            {"code":0,"data":{"replies":[{"rpid":1,"member":{"uname":"a"},"content":{"message":"1"}}]}}
        """.trimIndent()
        val result = BiliReplyParser.parsePage(parse(text))
        assertEquals(1, result.replies.size)
        assertFalse(result.hasMore)
    }

    @Test
    fun `空正文的楼层被丢弃`() {
        val text = """
            {"code":0,"data":{"cursor":{"next":0,"is_end":true,"all_count":2},
             "replies":[{"rpid":1,"member":{"uname":"a"},"content":{"message":"   "}},
                        {"rpid":2,"member":{"uname":"b"},"content":{"message":"活着"}}]}}
        """.trimIndent()
        val result = BiliReplyParser.parsePage(parse(text))
        assertEquals(1, result.replies.size)
        assertEquals("活着", result.replies.single().message)
    }

    @Test
    fun `没有昵称时显示匿名`() {
        val text = """{"rpid":9,"content":{"message":"x"}}"""
        val item = BiliReplyParser.parseItem(parse(text))
        assertEquals("匿名", item?.user)
    }

    @Test
    fun `楼中楼预览最多三条`() {
        val subs = (1..5).joinToString(",") {
            """{"rpid":$it,"member":{"uname":"u$it"},"content":{"message":"m$it"}}"""
        }
        val text = """{"rpid":1,"member":{"uname":"a"},"content":{"message":"主"},
          "replies":[$subs]}"""
        val item = BiliReplyParser.parseItem(parse(text))
        assertEquals(3, item?.preview?.size)
    }

    @Test
    fun `缺少 rpid 的节点不是评论`() {
        assertNull(BiliReplyParser.parseItem(parse("""{"member":{"uname":"a"}}""")))
    }
}
