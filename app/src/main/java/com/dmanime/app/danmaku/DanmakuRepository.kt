package com.dmanime.app.danmaku

import android.content.Context
import com.dmanime.app.BuildConfig
import com.dmanime.danmaku.BiliDanmakuClient
import com.dmanime.danmaku.DanDanPlayClient
import com.dmanime.danmaku.DanDanPlayEpisode
import com.dmanime.danmaku.Danmaku
import com.dmanime.danmaku.DanmakuEpisode
import com.dmanime.danmaku.DanmakuMatcher
import com.dmanime.danmaku.DanmakuOrigin
import com.dmanime.danmaku.DanmakuSeason
import com.dmanime.danmaku.UgcMatcher
import com.dmanime.danmaku.UgcVideo
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
    /** 配到的剧名，用来在界面上说清"弹幕来自哪"。 */
    val seasonTitle: String,
    val episodeTitle: String,
    /** 这一份里到底有哪些来源（多选时是合集）。 */
    val origins: Set<DanmakuOrigin> = emptySet(),
    /** 选了但没取到的来源，附一句原因——多选时不能悄悄少一个源。 */
    val failures: Map<DanmakuOrigin, String> = emptyMap(),
)

/**
 * 弹幕的取数、缓存与"这部剧在 B 站是哪个 season / 哪个投稿"的记忆。
 *
 * 三个来源各自独立取、独立缓存；用户选了几个就取几个，取到的按时间合并：
 * - [DanmakuOrigin.Catalog] 官方番剧库，标题准、集数全，但只有引进的片子；
 * - [DanmakuOrigin.Ugc] 用户投稿的分 P 合集，能补冷门番和国漫，时间轴天然对齐；
 * - [DanmakuOrigin.DanDanPlay] 弹弹play，聚合第三方站，需要 AppId。
 *
 * 缓存：
 * - 弹幕正文：`files/danmaku/{bgmId}-{集}-{来源}.json`，同一集同一来源不重复请求；
 * - 映射：`season:{bgmId}` / `ugc:{bgmId}` / `ddp:{bgmId}`，省掉每次开播都搜一遍；
 * - 集号映射：`ep:{bgmId}:{集}`，B 站分集顺序和第三方片源常差一两位（PV、总集篇）。
 */
class DanmakuRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "danmaku")
    private val prefs = appContext.getSharedPreferences("danmaku", Context.MODE_PRIVATE)
    private val client = BiliDanmakuClient()
    private val ddpClient = DanDanPlayClient(
        appId = BuildConfig.DANDANPLAY_APP_ID,
        secret = BuildConfig.DANDANPLAY_SECRET,
    )
    private val json = Json { ignoreUnknownKeys = true }

    /** 弹弹play 是否配了凭证（没配就别在界面上给这个选项）。 */
    val danDanPlayReady: Boolean get() = ddpClient.configured

    /** 界面上能勾的来源。没配弹弹play 就不列它，免得点了必然失败。 */
    val availableOrigins: Set<DanmakuOrigin>
        get() = if (danDanPlayReady) ORIGIN_ORDER.toSet() else ORIGIN_ORDER.toSet() - DanmakuOrigin.DanDanPlay

    fun cacheFile(subjectId: Int, episode: Int, origin: DanmakuOrigin): File =
        File(dir, "$subjectId-$episode-${origin.name}.json")

    /** 来源合并前的旧缓存文件，读的时候兜底用。 */
    private fun legacyCacheFile(subjectId: Int, episode: Int): File =
        File(dir, "$subjectId-$episode.json")

    /**
     * 取某一集的弹幕。
     *
     * 用户勾了几个来源就取几个：每个来源先看缓存，没有才发请求。
     * 全都取不到就返回 failure —— 界面据此**什么都不显示**，而不是套一份错的弹幕。
     */
    fun load(
        subjectId: Int,
        episode: Int,
        titles: List<String>,
        expectedEpisodes: Int,
        origins: Set<DanmakuOrigin>,
    ): Result<DanmakuBundle> {
        val queries = titles.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val wanted = origins.ifEmpty { setOf(DanmakuOrigin.Catalog) }
        if (queries.isEmpty()) return Result.failure(IllegalStateException("没有可用的番剧名"))

        val bundles = LinkedHashMap<DanmakuOrigin, DanmakuBundle>()
        val errors = LinkedHashMap<DanmakuOrigin, Throwable>()

        for (origin in ORIGIN_ORDER.filter { it in wanted }) {
            val cached = readCache(subjectId, episode, origin)
            if (cached != null) {
                bundles[origin] = cached
                continue
            }
            val result = when (origin) {
                DanmakuOrigin.Catalog -> loadFromCatalog(subjectId, episode, queries, expectedEpisodes)
                DanmakuOrigin.Ugc -> loadFromUgc(subjectId, episode, queries, expectedEpisodes)
                DanmakuOrigin.DanDanPlay -> loadFromDanDanPlay(subjectId, episode, queries)
            }
            result.onSuccess { bundles[origin] = it }.onFailure { errors[origin] = it }
        }

        if (bundles.isEmpty()) {
            val first = errors.values.firstOrNull()
            return Result.failure(first ?: IllegalStateException("没有找到弹幕"))
        }

        val merged = if (bundles.size == 1) {
            bundles.values.first().copy(failures = errors.mapValues { it.value.message ?: "取不到" })
        } else {
            val comments = bundles.values
                .flatMap { it.comments }
                .sortedBy { it.timeMs }
                .distinctBy { it.timeMs to it.text }
            DanmakuBundle(
                comments = comments,
                seasonTitle = bundles.values.map { it.seasonTitle }.filter { it.isNotBlank() }
                    .distinct().joinToString(" / "),
                episodeTitle = bundles.values.map { it.episodeTitle }.filter { it.isNotBlank() }
                    .distinct().joinToString(" / "),
                origins = bundles.keys.toSet(),
                failures = errors.mapValues { it.value.message ?: "取不到" },
            )
        }
        return Result.success(merged)
    }

    // ---------------- 官方番剧库 ----------------

    private fun loadFromCatalog(
        subjectId: Int,
        episode: Int,
        queries: List<String>,
        expectedEpisodes: Int,
    ): Result<DanmakuBundle> {
        val season = seasonFor(subjectId, queries, expectedEpisodes)
            ?: return Result.failure(IllegalStateException("番剧库没有这部剧"))

        val episodes = runCatching { client.episodes(season.seasonId) }.getOrElse {
            return Result.failure(it)
        }
        if (episodes.isEmpty()) return Result.failure(IllegalStateException("这一季没有可用的分集"))

        val target = pickEpisode(subjectId, episode, episodes)
            ?: return Result.failure(IllegalStateException("番剧库没有第 " + episode + " 集"))

        val comments = runCatching { client.comments(target.cid) }.getOrElse {
            return Result.failure(it)
        }
        if (comments.isEmpty()) return Result.failure(IllegalStateException("这一集还没有弹幕"))

        val bundle = DanmakuBundle(
            comments = comments,
            seasonTitle = season.title,
            episodeTitle = target.longTitle.ifBlank { target.title },
            origins = setOf(DanmakuOrigin.Catalog),
        )
        writeCache(subjectId, episode, DanmakuOrigin.Catalog, bundle)
        return Result.success(bundle)
    }

    private fun seasonFor(subjectId: Int, queries: List<String>, expectedEpisodes: Int): DanmakuSeason? {
        prefs.getLong("season:$subjectId", 0L).takeIf { it > 0 }?.let { cached ->
            val title = prefs.getString("seasonTitle:$subjectId", "").orEmpty()
            if (title.isNotBlank()) {
                return DanmakuSeason(cached, title, "", expectedEpisodes, 0.0, "", "", "")
            }
        }

        for (query in queries) {
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

    // ---------------- UGC 区投稿 ----------------

    /**
     * 番剧库没有的片子走这里：UP 主把合集切成分 P，每个 P 就是一集。
     *
     * 好处是时间轴天然对齐（每集从 0 开始）；代价是搬运质量参差，
     * 所以先用 [UgcMatcher] 把标题和分 P 结构都过一遍，宁可没有也不挂错。
     */
    private fun loadFromUgc(
        subjectId: Int,
        episode: Int,
        queries: List<String>,
        expectedEpisodes: Int,
    ): Result<DanmakuBundle> {
        val video = ugcVideoFor(subjectId, queries, expectedEpisodes)
            ?: return Result.failure(IllegalStateException("用户投稿里没有对得上的搬运"))
        val pages = runCatching { client.pages(video.bvid) }.getOrElse {
            return Result.failure(it)
        }
        if (pages.isEmpty()) return Result.failure(IllegalStateException("这个视频没有分 P"))

        // 分 P 的序号就是 page，正常情况一一对应；缺号时退回按顺序取
        val target = pages.firstOrNull { it.index == episode }
            ?: pages.getOrNull(episode - 1)
            ?: return Result.failure(IllegalStateException("搬运只到第 " + pages.size + " 集"))

        val comments = runCatching { client.comments(target.cid) }.getOrElse {
            return Result.failure(it)
        }
        if (comments.isEmpty()) return Result.failure(IllegalStateException("这一集还没有弹幕"))

        val bundle = DanmakuBundle(
            comments = comments,
            seasonTitle = video.title,
            episodeTitle = target.title,
            origins = setOf(DanmakuOrigin.Ugc),
        )
        writeCache(subjectId, episode, DanmakuOrigin.Ugc, bundle)
        return Result.success(bundle)
    }

    private fun ugcVideoFor(subjectId: Int, queries: List<String>, expectedEpisodes: Int): UgcVideo? {
        prefs.getString("ugc:$subjectId", null)?.takeIf { it.isNotBlank() }?.let { bvid ->
            val title = prefs.getString("ugcTitle:$subjectId", "").orEmpty()
            return UgcVideo(bvid, title, 0, 0)
        }

        for (query in queries) {
            val candidates = runCatching { client.searchUgc(query) }.getOrDefault(emptyList())
            if (candidates.isEmpty()) continue
            // 标题对上的按播放量排，逐个看分 P 结构；命中的那几个才值得多花一次请求
            for (video in UgcMatcher.rank(query, candidates).take(MAX_UGC_PROBES)) {
                val pages = runCatching { client.pages(video.bvid) }.getOrDefault(emptyList())
                val durations = pages.map { (it.durationMs / 1000).toInt() }
                if (UgcMatcher.structureScore(durations, expectedEpisodes) <= 0) continue
                prefs.edit()
                    .putString("ugc:$subjectId", video.bvid)
                    .putString("ugcTitle:$subjectId", video.title)
                    .apply()
                return video
            }
        }
        return null
    }

    // ---------------- 弹弹play ----------------

    private fun loadFromDanDanPlay(
        subjectId: Int,
        episode: Int,
        queries: List<String>,
    ): Result<DanmakuBundle> {
        if (!ddpClient.configured) {
            return Result.failure(IllegalStateException("没有配置弹弹play 的 AppId"))
        }

        val cached = prefs.getLong("ddp:$subjectId:$episode", 0L).takeIf { it > 0 }
        val episodeId = cached ?: run {
            // 搜索里的鉴权/网络异常要原样抛出去：403 Invalid AppId 被吞成
            // "没有匹配到这部剧"，会让人以为是片库里真没有，其实是应用还没过审
            val match = try {
                searchDanDanPlay(subjectId, episode, queries)
            } catch (e: Exception) {
                return Result.failure(e)
            } ?: return Result.failure(IllegalStateException("弹弹play 没有匹配到这部剧"))
            prefs.edit().putLong("ddp:$subjectId:$episode", match.episodeId).apply()
            match.episodeId
        }

        val comments = runCatching { ddpClient.comments(episodeId) }.getOrElse {
            return Result.failure(it)
        }
        if (comments.isEmpty()) return Result.failure(IllegalStateException("弹弹play 这一集还没有弹幕"))

        val title = prefs.getString("ddpTitle:$subjectId", "").orEmpty()
        val episodeTitle = prefs.getString("ddpEp:$subjectId:$episode", "").orEmpty()
        val bundle = DanmakuBundle(
            comments = comments,
            seasonTitle = title.ifBlank { queries.first() },
            episodeTitle = episodeTitle,
            origins = setOf(DanmakuOrigin.DanDanPlay),
        )
        writeCache(subjectId, episode, DanmakuOrigin.DanDanPlay, bundle)
        return Result.success(bundle)
    }

    /** 抛异常表示"请求本身失败"（鉴权/网络），返回 null 才是"搜到但没对得上的"。 */
    private fun searchDanDanPlay(
        subjectId: Int,
        episode: Int,
        queries: List<String>,
    ): DanDanPlayEpisode? {
        for (query in queries) {
            val animes = ddpClient.searchEpisodes(query, episode)
            for (anime in animes) {
                // 标题要和番剧名对得上，否则宁可不选
                if (!UgcMatcher.titleOk(query, anime.animeTitle)) continue
                val target = anime.episodes.firstOrNull() ?: continue
                prefs.edit()
                    .putString("ddpTitle:$subjectId", anime.animeTitle)
                    .putString("ddpEp:$subjectId:$episode", target.episodeTitle)
                    .apply()
                return target
            }
        }
        return null
    }

    // ---------------- 缓存 ----------------

    private fun readCache(subjectId: Int, episode: Int, origin: DanmakuOrigin): DanmakuBundle? {
        val file = cacheFile(subjectId, episode, origin).takeIf { it.exists() && it.length() > 0L }
            ?: legacyCacheFile(subjectId, episode).takeIf {
                origin == DanmakuOrigin.Catalog && it.exists() && it.length() > 0L
            }
            ?: return null

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
                origins = setOf(origin),
            )
        }.getOrNull()
    }

    private fun writeCache(
        subjectId: Int,
        episode: Int,
        origin: DanmakuOrigin,
        bundle: DanmakuBundle,
    ) {
        runCatching {
            dir.mkdirs()
            val root = buildJsonObject {
                put("season", bundle.seasonTitle)
                put("episode", bundle.episodeTitle)
                put("origin", origin.name)
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
            cacheFile(subjectId, episode, origin).writeText(root.toString())
        }
    }

    /** 用户手动改过的映射要能清掉（换源/换番重新匹配）。 */
    fun forget(subjectId: Int) {
        prefs.edit()
            .remove("season:$subjectId")
            .remove("seasonTitle:$subjectId")
            .remove("ugc:$subjectId")
            .remove("ugcTitle:$subjectId")
            .remove("ddpTitle:$subjectId")
            .apply()
        runCatching {
            dir.listFiles()?.filter { it.name.startsWith("$subjectId-") }?.forEach { it.delete() }
        }
    }

    fun cachedEpisodeCount(subjectId: Int): Int = runCatching {
        dir.listFiles()
            ?.mapNotNull { it.name.removePrefix("$subjectId-").substringBefore('-').toIntOrNull() }
            ?.toSet()?.size ?: 0
    }.getOrDefault(0)

    private companion object {
        /** 一个来源最多试几个候选投稿，每个要多一次请求，多了很慢。 */
        const val MAX_UGC_PROBES = 3

        val ORIGIN_ORDER = listOf(
            DanmakuOrigin.Catalog,
            DanmakuOrigin.Ugc,
            DanmakuOrigin.DanDanPlay,
        )
    }
}
