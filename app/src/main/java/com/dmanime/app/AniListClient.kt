package com.dmanime.app

import android.content.Context
import com.dmanime.catalog.CatalogSubject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 「下一集什么时候播」。Bangumi 只到「星期几」，这个数据只有 AniList 有。 */
data class AiringInfo(
    val episode: Int,
    val airingAt: Long,
    val timeUntil: Long,
    val anilistId: Int,
    val romaji: String,
)

/**
 * AniList 客户端：把 Bangumi 条目映射到 AniList，再取 nextAiringEpisode。
 *
 * 匹配策略（实测 109 部里 67 部能对上）：
 * - Bangumi 的 `name` 是日文原名，AniList 的 `title.native` 也是 → 归一化后精确相等算 A 级；
 * - 罗马字 / 别名相等算 B 级；
 * - 其余一律不用（宁可不显示，也不显示错的）。
 * - 日期校验：两边都有首播日时，相差 ≤7 天才采信。
 *
 * 映射结果按 Bangumi id 缓存，第二次打开详情不再搜索。
 */
class AniListClient(context: Context) {

    private val prefs = context.getSharedPreferences("anilist", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun nextAiring(subject: CatalogSubject): AiringInfo? {
        val cachedId = prefs.getInt("map:${subject.id}", 0)
        if (cachedId > 0) {
            return fetchAiring(cachedId)
        }
        val name = subject.name.ifBlank { subject.nameCn }
        if (name.isBlank()) return null

        val media = search(name)
        val best = pick(name, media) ?: return null
        val anilistId = best["id"]?.jsonPrimitive?.intOrNull ?: return null

        // 日期校验：首播日差太多说明是不同季 / 不同版本
        if (!dateCompatible(subject.airDate, best)) {
            log("AniList 匹配被日期否决：$name -> ${best["title"]}")
            return null
        }

        prefs.edit().putInt("map:${subject.id}", anilistId).apply()
        return fetchAiring(anilistId)
    }

    private fun fetchAiring(anilistId: Int): AiringInfo? {
        val query = """
            query{Media(id:$anilistId,type:ANIME){
              id title{romaji}
              nextAiringEpisode{episode airingAt timeUntilAiring}
            }}
        """.trimIndent()
        val data = post(query)?.get("Media")?.jsonObject ?: return null
        val next = data["nextAiringEpisode"] as? JsonObject ?: return null
        return AiringInfo(
            episode = next["episode"]?.jsonPrimitive?.intOrNull ?: return null,
            airingAt = next["airingAt"]?.jsonPrimitive?.longOrNull ?: return null,
            timeUntil = next["timeUntilAiring"]?.jsonPrimitive?.longOrNull ?: 0L,
            anilistId = anilistId,
            romaji = data["title"]?.jsonObject?.get("romaji")?.jsonPrimitive?.content.orEmpty(),
        )
    }

    private fun search(term: String): List<JsonObject> {
        val query = """
            query{Page(perPage:10){media(search:${JsonPrimitive(term)},type:ANIME){
              id title{romaji native english} synonyms startDate{year month day}
            }}}
        """.trimIndent()
        return post(query)?.get("Page")?.jsonObject
            ?.get("media")?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
    }

    private fun pick(name: String, media: List<JsonObject>): JsonObject? {
        val target = norm(name)
        media.firstOrNull { m ->
            norm(m["title"]?.jsonObject?.get("native")?.jsonPrimitive?.content.orEmpty()) == target
        }?.let { return it }
        return media.firstOrNull { m ->
            val title = m["title"]?.jsonObject
            val cands = listOfNotNull(
                title?.get("romaji")?.jsonPrimitive?.content,
                title?.get("english")?.jsonPrimitive?.content,
            ) + (m["synonyms"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList())
            cands.any { norm(it) == target }
        }
    }

    private fun dateCompatible(airDate: String, m: JsonObject): Boolean {
        if (airDate.isBlank()) return true
        val start = m["startDate"]?.jsonObject ?: return true
        val year = start["year"]?.jsonPrimitive?.intOrNull ?: return true
        return runCatching {
            val a = LocalDate.parse(airDate)
            val b = LocalDate.of(
                year,
                start["month"]?.jsonPrimitive?.intOrNull ?: 1,
                start["day"]?.jsonPrimitive?.intOrNull ?: 1,
            )
            kotlin.math.abs(ChronoUnit.DAYS.between(a, b)) <= 7
        }.getOrDefault(true)
    }

    private fun norm(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    private fun post(query: String): JsonObject? = runCatching {
        val conn = (URL("https://graphql.anilist.co").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", DEFAULT_UA)
        }
        val body = "{\"query\":" + JsonPrimitive(query) + "}"
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode != 200) {
            log("AniList HTTP ${conn.responseCode}")
            return null
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        json.parseToJsonElement(text).jsonObject["data"] as? JsonObject
    }.onFailure { log("AniList 请求失败：${it.message}") }.getOrNull()

    private fun log(message: String) {
        android.util.Log.d("dmanime", message)
    }
}
