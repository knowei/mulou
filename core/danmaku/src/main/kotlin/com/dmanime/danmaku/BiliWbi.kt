package com.dmanime.danmaku

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * B 站 wbi 签名。
 *
 * 2023 年起 `x/web-interface/search/type` 等接口要求 wbi 签名，否则一律 412/风控页。
 * 算法：从 `x/web-interface/nav` 拿 img_key / sub_key，按固定的 MIXIN 表重排出 32 位
 * mixinKey；再把参数按 key 字典序拼成 querystring + mixinKey 做 md5 得到 `w_rid`。
 */
object BiliWbi {

    private val MIXIN = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62,
        11, 36, 20, 34, 44, 52,
    )

    fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        val sb = StringBuilder(32)
        for (i in MIXIN) {
            if (i < raw.length) sb.append(raw[i])
            if (sb.length == 32) break
        }
        return sb.toString()
    }

    /** 在 params 上补齐 `wts` 与 `w_rid`，返回可直接拼到 URL 的完整参数表。 */
    fun sign(params: Map<String, String>, wts: Long, mixinKey: String): Map<String, String> {
        val withTs = LinkedHashMap<String, String>(params.size + 2)
        withTs.putAll(params)
        withTs["wts"] = wts.toString()

        val query = withTs.entries
            .sortedBy { it.key }
            .joinToString("&") { (k, v) -> "${encode(k)}=${encode(clean(v))}" }

        withTs["w_rid"] = md5(query + mixinKey)
        return withTs
    }

    /** wbi 要求剔除 value 里的 `!'()*` 再参与签名。 */
    private fun clean(value: String): String =
        value.filterNot { it == '!' || it == '\'' || it == '(' || it == ')' || it == '*' }

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
