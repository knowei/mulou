package com.dmanime.danmaku

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 一条评论区留言（B 站 `x/v2/reply/wbi/main`）。
 *
 * 和弹幕的区别：弹幕挂在**分 P 的 cid** 上（所以每集一条时间轴），
 * 评论挂在**整个稿件/剧集的 aid** 上。番剧库里每集各有自己的 aid，
 * 所以「每集评论区」在番剧这条路上是天然成立的。
 */
data class BiliReply(
    val rpid: Long,
    val user: String,
    val avatar: String,
    val message: String,
    val like: Int,
    val ctime: Long,
    val replyCount: Int,
    /** 例如「IP属地：上海」，B 站没给就是空串。 */
    val location: String,
    /** 楼中楼预览，接口每层最多带 3 条。 */
    val preview: List<BiliReply> = emptyList(),
)

data class BiliReplyPage(
    val replies: List<BiliReply>,
    /** 下一页游标，0 表示没有下一页。 */
    val next: Long,
    val hasMore: Boolean,
    val total: Int,
) {
    companion object {
        val EMPTY = BiliReplyPage(emptyList(), 0, false, 0)
    }
}

/**
 * 评论接口的解析。单独抽出来是因为 B 站的字段嵌套很深，
 * 且不同端返回的结构略有差异（有的给 `top_replies`，有的只给 `replies`）。
 */
object BiliReplyParser {

    fun parsePage(root: JsonObject): BiliReplyPage {
        // code != 0 时 data 是 null，视为空页而不是异常
        val data = root["data"] as? JsonObject ?: return BiliReplyPage.EMPTY
        val cursor = data["cursor"] as? JsonObject
        val total = cursor?.int("all_count") ?: 0
        val next = cursor?.long("next") ?: 0
        val isEnd = cursor?.bool("is_end") ?: (next <= 0)

        val list = (data["replies"] as? JsonArray).orEmpty()
            .mapNotNull { parseItem(it) }
        return BiliReplyPage(
            replies = list,
            next = next,
            hasMore = !isEnd && next > 0,
            total = total,
        )
    }

    fun parseItem(element: JsonElement): BiliReply? {
        val obj = element as? JsonObject ?: return null
        val rpid = obj.long("rpid") ?: return null
        val member = obj["member"] as? JsonObject
        val content = obj["content"] as? JsonObject
        val message = content?.string("message").orEmpty().trim()
        if (message.isEmpty()) return null
        val preview = (obj["replies"] as? JsonArray).orEmpty()
            .mapNotNull { parseItem(it) }
            .take(3)
        return BiliReply(
            rpid = rpid,
            user = member?.string("uname").orEmpty().ifBlank { "匿名" },
            avatar = member?.string("avatar").orEmpty(),
            message = message,
            like = obj.int("like"),
            ctime = obj.long("ctime") ?: 0L,
            replyCount = obj.int("rcount"),
            location = (obj["reply_control"] as? JsonObject)
                ?.string("location").orEmpty()
                .removePrefix("IP属地："),
            preview = preview,
        )
    }

    private fun JsonObject.string(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull().orEmpty()

    private fun JsonObject.int(key: String): Int =
        runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 0

    private fun JsonObject.long(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
