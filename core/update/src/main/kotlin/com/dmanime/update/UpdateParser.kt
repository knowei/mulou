package com.dmanime.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** 一次发布：版本号、更新说明、两个链接。 */
data class UpdateRelease(
    /** 去掉 v 前缀的版本号，例如 1.1 */
    val version: String,
    /** release 正文（markdown 原文），界面按纯文本显示 */
    val notes: String,
    /** 发布页，人看的 */
    val pageUrl: String,
    /** 直链 APK；没挂附件时为空串 */
    val apkUrl: String,
)

/**
 * 解析 GitHub `repos/{owner}/{repo}/releases` 的返回。
 *
 * 规则：
 * - 草稿（draft）和预发布（prerelease）跳过——那是不该推给用户的
 * - 版本号取 tag_name，去掉 v 前缀
 * - APK 取第一个 `.apk` 附件；一个都没有就留空，界面退化成「打开页面」
 */
object UpdateParser {

    fun parseLatest(json: String): UpdateRelease? {
        val releases = runCatching { Json.parseToJsonElement(json).jsonArray.toList() }.getOrNull()
            ?: return null
        return releases
            .mapNotNull { it as? JsonObject }
            .filter { obj ->
                val draft = obj["draft"]?.jsonPrimitive?.booleanOrNull ?: false
                val pre = obj["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false
                !draft && !pre
            }
            .mapNotNull { toRelease(it) }
            .firstOrNull()
    }

    private fun toRelease(obj: JsonObject): UpdateRelease? {
        val version = AppVersion.strip(obj.text("tag_name") ?: "")
        if (version.isBlank()) return null
        val apk = (obj["assets"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.text("name")?.endsWith(".apk", ignoreCase = true) == true }
            ?.text("browser_download_url")
            .orEmpty()
        return UpdateRelease(
            version = version,
            notes = obj.text("body").orEmpty().trim(),
            pageUrl = obj.text("html_url").orEmpty(),
            apkUrl = apk,
        )
    }

    private fun JsonObject.text(key: String): String? = this[key].str()
}

private fun JsonElement?.str(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content
    else -> null
}

/**
 * 版本号比较。
 *
 * 只比数字段，`1.10 > 1.9`（不是字符串比较）；缺的段按 0 算，
 * 所以 `1.1` 和 `1.1.0` 相等。预发布后缀（`-beta1`）比较时忽略——
 * 反正 [UpdateParser] 已经把预发布过滤掉了。
 */
object AppVersion {

    fun strip(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V").trim()

    fun isNewer(remote: String, current: String): Boolean {
        val a = segments(remote)
        val b = segments(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun segments(version: String): List<Int> =
        strip(version)
            .substringBefore('-')
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}
