package com.dmanime.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Bangumi v0 客户端。
 *
 * P0 只用一个接口：`GET /calendar` 一次拿到 7 天 × 当季番剧，
 * 既有"今日更新"也有"本季新番"，够首页用，不必再调季度接口。
 *
 * 注意：Bangumi 要求带 User-Agent，否则会被拒。
 */
class BangumiClient(private val fetch: (url: String) -> String) {

    /**
     * `coerceInputValues` 是必须的：Bangumi 的条目里会出现 `"images": null`、
     * `"rating": null`、`"name_cn": null`，而 kotlinx 默认会因此丢掉整个列表。
     * 实测一次 `null images` 就让首页整页空白。
     */
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun calendar(): List<CalendarDay> = parseCalendar(fetch("$BASE/calendar"))

    /** 解析与抓取分开：宿主可以自己取文本（带缓存/重试），这里只负责解析。 */
    fun parseCalendar(text: String): List<CalendarDay> =
        json.decodeFromString<List<CalendarDay>>(text).sortedBy { it.weekday.id }

    fun subject(id: Int): SubjectDetail = parseSubject(fetch("$BASE/v0/subjects/$id"))

    fun parseSubject(text: String): SubjectDetail = json.decodeFromString<SubjectDetail>(text)

    fun parseSearch(text: String): SearchResponse = json.decodeFromString<SearchResponse>(text)

    /**
     * 角色 + 声优。
     *
     * 注意形状不统一：v0 原生返回数组，而反代（api.bgm.tv 老接口）可能返回
     * `{"value":[...],"Count":n}`，两种都要能吃。
     */
    fun parseCharacters(text: String): List<SubjectCharacter> {
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val array = when (element) {
            is JsonArray -> element
            is JsonObject -> element["value"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { item ->
            runCatching { json.decodeFromJsonElement(SubjectCharacter.serializer(), item) }.getOrNull()
        }
    }

    companion object {
        const val BASE = "https://api.bgm.tv"
        const val USER_AGENT = "dmanime/0.1 (Android; personal use)"
    }
}
