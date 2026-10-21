package com.dmanime.app.danmaku

import android.content.Context
import com.dmanime.danmaku.BiliDanmakuClient
import com.dmanime.danmaku.Danmaku
import com.dmanime.danmaku.DanmakuEpisode
import com.dmanime.danmaku.DanmakuMatcher
import com.dmanime.danmaku.DanmakuSeason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/** 一集弹幕的加载结果。 */
data class DanmakuBundle(
    val comments: List<Danmaku>,
    /** 配到的 B 站剧名，用来在界面上说清"弹幕来自哪"。 */
    val seasonTitle: String,
    val episodeTitle: String,
)

/**
 * 弹幕的取数、缓存与"这部剧在 B 站是哪个 season"的记忆。
 *
 * 三条缓存：
 * - 弹幕正文：`files/danmaku/{bgmId}-{集}.json`，同一集再看不重复请求；
 * - 番剧映射：`season:{bgmId}`，省掉每次开播都搜一遍；
 * - 集号映射：`ep:{bgmId}:{集}`，B 站分集顺序和第三方片源常差一两位（PV、总集篇），
 *   记住一次就不用每次重新对。
 */
class DanmakuRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "danmaku")
    private val prefs = appContext.getSharedPreferences("danmaku", Context.MODE_PRIVATE)
    private val client = BiliDanmakuClient()
    private val json = Json { ignoreUnknownKeys = true }

    fun cacheFile(subjectId: Int, episode: Int): File =
        File(dir, "$subjectId-$episode.json")

    /**
     * 取某一集的弹幕。命中缓存直接返回；否则去 B 站找。
     * 匹配不上返回 failure —— 界面据此**什么都不显示**，而不是套一份错的弹幕。
     */
    fun load(
        subjectId: Int,
        episode: Int,
        titles: List<String>,
        expectedEpisodes: Int,
    ): Result<DanmakuBundle> {
        readCache(subjectId, episode)?.let { return Result.success(it) }

        val season = seasonFor(subjectId, titles, expectedEpisodes)
            ?: return Result.failure(IllegalStateException("B 站没有匹配到这部剧"))

        val episodes = runCatching { client.episodes(season.seasonId) }.getOrElse {
            return Result.failure(it)
        }
        if (episodes.isEmpty()) return Result.failure(IllegalStateException("这一季没有可用的分集"))

        val target = pickEpisode(subjectId, episode, episodes)
            ?: return Result.failure(IllegalStateException("B 站没有第 " + episode + " 集"))

        val comments = runCatching { client.comments(target.cid) }.getOrElse {
            return Result.failure(it)
        }
        if (comments.isEmpty()) return Result.failure(IllegalStateException("这一集还没有弹幕"))

        val bundle = DanmakuBundle(comments, season.title, target.longTitle.ifBlank { target.title })
        writeCache(subjectId, episode, bundle)
        return Result.success(bundle)
    }

    private fun seasonFor(subjectId: Int, titles: List<String>, expectedEpisodes: Int): DanmakuSeason? {
        prefs.getLong("season:$subjectId", 0L).takeIf { it > 0 }?.let { cached ->
            val title = prefs.getString("seasonTitle:$subjectId", "").orEmpty()
            if (title.isNotBlank()) {
                return DanmakuSeason(cached, title, "", expectedEpisodes, 0.0, "", "", "")
            }
        }

        for (query in titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()) {
            val candidates = runCatching { client.search(query) }.getOrDefault(emptyList())
            val pick = DanmakuMatcher.best(query, candidates, expectedEpisodes) ?: continue
            prefs.edit()
                .putLong("season:$subjectId", pick.seasonId)
                .putString("seasonTitle:$subjectId", pick.title)
                .apply()
            return pick
        }
        return null
    }

    private fun pickEpisode(subjectId: Int, episode: Int, list: List<DanmakuEpisode>): DanmakuEpisode? {
        // 记过就直接用
        prefs.getLong("ep:$subjectId:$episode", 0L).takeIf { it > 0 }?.let { cid ->
            list.firstOrNull { it.cid == cid }?.let { return it }
        }
        list.firstOrNull { it.index == episode }?.let { return it }
        // B 站集号有缺号（总集篇、特别篇占位）时，退回按顺序取
        list.getOrNull(episode - 1)?.let { return it }
        return null
    }

    // ---------------- 缓存 ----------------

    private fun readCache(subjectId: Int, episode: Int): DanmakuBundle? {
        val file = cacheFile(subjectId, episode)
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val array = root["comments"]?.jsonArray ?: JsonArray(emptyList())
            val comments = array.map { element ->
                val obj = element.jsonObject
                Danmaku(
                    timeMs = obj["t"]!!.jsonPrimitive.long,
                    mode = when (obj["m"]?.jsonPrimitive?.int ?: 0) {
                        1 -> Danmaku.Mode.Top
                        2 -> Danmaku.Mode.Bottom
                        else -> Danmaku.Mode.Scroll
                    },
                    color = obj["c"]?.jsonPrimitive?.int ?: 0xFFFFFFFF.toInt(),
                    text = obj["s"]!!.jsonPrimitive.content,
                )
            }
            DanmakuBundle(
                comments = comments,
                seasonTitle = root["season"]?.jsonPrimitive?.content.orEmpty(),
                episodeTitle = root["episode"]?.jsonPrimitive?.content.orEmpty(),
            )
        }.getOrNull()
    }

    private fun writeCache(subjectId: Int, episode: Int, bundle: DanmakuBundle) {
        runCatching {
            dir.mkdirs()
            val root = buildJsonObject {
                put("season", bundle.seasonTitle)
                put("episode", bundle.episodeTitle)
                put(
                    "comments",
                    buildJsonArray {
                        bundle.comments.forEach { item ->
                            add(
                                buildJsonObject {
                                    put("t", item.timeMs)
                                    put(
                                        "m",
                                        when (item.mode) {
                                            Danmaku.Mode.Scroll -> 0
                                            Danmaku.Mode.Top -> 1
                                            Danmaku.Mode.Bottom -> 2
                                        },
                                    )
                                    put("c", item.color)
                                    put("s", item.text)
                                },
                            )
                        }
                    },
                )
            }
            cacheFile(subjectId, episode).writeText(root.toString())
        }
    }

    /** 用户手动改过的映射要能清掉（换源/换番重新匹配）。 */
    fun forget(subjectId: Int) {
        prefs.edit()
            .remove("season:$subjectId")
            .remove("seasonTitle:$subjectId")
            .apply()
        runCatching { dir.listFiles()?.filter { it.name.startsWith("$subjectId-") }?.forEach { it.delete() } }
    }

    fun cachedEpisodeCount(subjectId: Int): Int =
        runCatching { dir.listFiles()?.count { it.name.startsWith("$subjectId-") } ?: 0 }.getOrDefault(0)

    private fun JsonObject.stringOrEmpty(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.content.orEmpty() }.getOrDefault("")
}
