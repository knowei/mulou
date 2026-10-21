package com.dmanime.app

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dmanime.app.download.AppDownloadService
import com.dmanime.app.download.DownloadItem
import com.dmanime.app.download.DownloadMeta
import com.dmanime.app.download.DownloadUtil
import com.dmanime.app.download.SubjectMeta
import com.dmanime.app.download.SubjectMetaStore
import com.dmanime.app.danmaku.DanmakuRepository
import com.dmanime.catalog.CalendarDay
import com.dmanime.catalog.CatalogSubject
import com.dmanime.catalog.SubjectCharacter
import com.dmanime.catalog.Images
import com.dmanime.catalog.SearchResult
import com.dmanime.catalog.SubjectDetail
import com.dmanime.danmaku.Danmaku
import com.dmanime.danmaku.DanmakuOrigin
import com.dmanime.rules.css1.Css1Converter
import com.dmanime.rules.engine.RuleEngine
import com.dmanime.rules.model.AnimeRule
import com.dmanime.rules.model.Road
import com.dmanime.rules.model.RuleJson
import com.dmanime.rules.model.SearchItem
import com.dmanime.rules.model.encodeRule
import com.dmanime.rules.model.parseRule
import com.dmanime.rules.net.HttpUrlConnectionClient
import com.dmanime.sniff.MediaKind
import com.dmanime.sniff.MediaUrls
import com.dmanime.sniff.SniffCandidate
import com.dmanime.sniff.SniffResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class Screen { Home, Search, Mine, Downloads, Detail, Chapters, Player, Verify, Health, Season }

/** 一个源的体检结果。status: ok / empty / error */
data class SourceHealth(
    val rule: String,
    val status: String,
    val items: Int = 0,
    val roads: Int = 0,
    val error: String? = null,
    val latencyMs: Long = 0,
)

/** 搜索页的两种模式：本地索引 / 直接搜片源。 */
enum class SearchMode { Index, Sources }

/** 播放页状态机。 */
enum class PlayPhase { Idle, Searching, Starting, Playing, Failed }

data class PlayMedia(val url: String, val headers: Map<String, String>, val note: String = "")

/** 索引层状态。缓存优先：days 有值就能渲染，loading/error 只是叠加状态。 */
data class HomeState(
    val loading: Boolean = false,
    val days: List<CalendarDay> = emptyList(),
    val error: String? = null,
    val updatedAt: Long = 0L,
)

/** 为什么点不了/播不了这一集。 */
enum class GateReason { NotAired, SourceBehind, NoSource }

data class EpisodeGate(
    val reason: GateReason,
    val requested: Int,
    val expected: Int,
    val sourceMax: Int,
    val ruleName: String = "",
    val entrySrc: String = "",
    val entryName: String = "",
    val rule: AnimeRule? = null,
)

/** 一个源的探测结果：线路、耗时、失败原因。 */
data class SourceStatus(
    val probing: Boolean = false,
    val roads: List<Road> = emptyList(),
    val error: String? = null,
    val latencyMs: Long = 0,
) {
    val failed: Boolean get() = error != null
    val ready: Boolean get() = roads.isNotEmpty() && error == null
}

/** 一个源的搜索结果。失败/空结果都保留，直接展示在原位。 */
data class SourceHit(
    val rule: AnimeRule,
    val ruleName: String,
    val items: List<SearchItem> = emptyList(),
    val error: String? = null,
    val done: Boolean = false,
)

data class UiState(
    val screen: Screen = Screen.Home,
    val rules: List<AnimeRule> = emptyList(),
    val selected: AnimeRule? = null,
    val keyword: String = "",
    val busy: Boolean = false,
    val hits: List<SourceHit> = emptyList(),
    val completed: Int = 0,
    val total: Int = 0,
    val roads: List<Road> = emptyList(),
    val title: String = "",
    val playPageUrl: String = "",
    val playMedia: PlayMedia? = null,
    val logs: List<String> = emptyList(),
    val error: String? = null,
    val home: HomeState = HomeState(),
    val detail: CatalogSubject? = null,
    val detailOrigin: Screen = Screen.Home,
    /** 详情页返回栈：打开详情时压入来源屏，返回时弹回。 */
    val detailStack: List<Screen> = emptyList(),
    val indexBaseUrl: String = "",
    val indexProbe: String = "",
    val imageBase: String = "",
    val detailInfo: SubjectDetail? = null,
    val detailLoading: Boolean = false,
    val watches: List<WatchEntry> = emptyList(),
    /** 正在后台探"追番的源更新到第几集"。 */
    val watchProbing: Boolean = false,
    val playSubjectId: Int = 0,
    val playRoadIndex: Int = 0,
    val playEpisodeIndex: Int = -1,
    val playStartMs: Long = 0,
    val playPhase: PlayPhase = PlayPhase.Idle,
    val episodeNumber: Int = 1,
    val failedSources: Set<String> = emptySet(),
    val autoSwitches: Int = 0,
    val searchMode: SearchMode = SearchMode.Index,
    val playQuery: String = "",
    val onlineResults: List<SearchResult> = emptyList(),
    val onlineSearching: Boolean = false,
    val entryRuleName: String = "",
    val entrySrc: String = "",
    val entryName: String = "",
    val playerFullscreen: Boolean = false,
    val sourceStatus: Map<String, SourceStatus> = emptyMap(),
    val searchHistory: List<String> = emptyList(),
    /** 从播放页返回时该回到哪一屏。 */
    val playerBackTo: Screen = Screen.Detail,
    val showFailedSources: Boolean = false,
    val verifyUrl: String = "",
    val verifyRuleName: String = "",
    val verifyDone: Boolean = false,
    val episodeSheet: Boolean = false,
    val episodeLoading: Boolean = false,
    val episodeGate: EpisodeGate? = null,
    /** 下一集什么时候播（AniList）。没用/匹配不上时为 null。 */
    val airing: AiringInfo? = null,
    val characters: List<SubjectCharacter> = emptyList(),
    // ---------- 弹幕 ----------
    val danmakuOn: Boolean = true,
    val danmakuAlpha: Float = 0.92f,
    val danmakuScale: Float = 1f,
    /** 弹幕占视频画面的高度比例：0.3 只在顶部一窄条，1.0 铺满整个画面。 */
    val danmakuArea: Float = 0.62f,
    /** 滚动速度倍率：1.0 正常，越大越快。 */
    val danmakuSpeed: Float = 1f,
    /** 手动对齐用的时间偏移（毫秒）。片头长短不一，自动对不准时让用户自己挪。 */
    val danmakuOffsetMs: Long = 0,
    val danmakuComments: List<Danmaku> = emptyList(),
    val danmakuLoading: Boolean = false,
    /** 非空表示这一集没取到弹幕（B 站没有这部剧/这集），界面据此什么都不显示。 */
    val danmakuError: String = "",
    /** 弹幕来源说明，例如「番剧库 · 葬送的芙莉莲 第5话」。 */
    val danmakuSource: String = "",
    /**
     * 用户勾选的弹幕来源。勾一个就只显示那一个，勾多个按时间合并。
     * 默认走官方番剧库——标题和集数最准。
     */
    val danmakuOrigins: Set<DanmakuOrigin> = setOf(DanmakuOrigin.Catalog),
    /** 当前能选的来源。弹弹play 没配凭证就不列出来，避免点了必然失败。 */
    val danmakuPickable: Set<DanmakuOrigin> = setOf(DanmakuOrigin.Catalog, DanmakuOrigin.Ugc),
    /** 勾了但没取到的来源及原因，例如「用户投稿：没找到对得上的搬运」。 */
    val danmakuMissing: String = "",
    /** 主题：light / dark / system */
    val themeMode: String = "light",
    /** 导航方向：true=前进（新页面从右滑入），false=返回（从左滑入） */
    val navForward: Boolean = true,
    /**
     * 二级页返回栈。进源体检/季度番剧/下载/人机验证时把当前屏压进来，
     * 返回时弹回上一屏——否则这些页面点「返回」会没反应（back() 没有分支可落）。
     */
    val navStack: List<Screen> = emptyList(),
    val seasonYear: Int = 0,
    val seasonQuarter: Int = 0,
    val seasonItems: List<CatalogSubject> = emptyList(),
    val seasonTotal: Int = 0,
    val seasonLoading: Boolean = false,
    val seasonError: String? = null,
    val health: List<SourceHealth> = emptyList(),
    val healthBusy: Boolean = false,
    val healthKeyword: String = "",
    val healthDeep: Boolean = false,
    val alerts: List<EpisodeAlert> = emptyList(),
    /** 下载队列（media3 Download 的投影）。 */
    val downloads: List<DownloadItem> = emptyList(),
    /** 已落盘的番剧元信息（标题/简介/本地封面），按 subjectId 索引。 */
    val downloadSubjects: Map<Int, SubjectMeta> = emptyMap(),
    /** 下载页顶部的一次性提示，如"这一集还没解析出地址"。 */
    val downloadTip: String = "",
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        /** 自动换源上限：超过就停下来让用户决定，避免无限重试。 */
        const val MAX_AUTO_SWITCH = 3

        /** 起播前的收敛窗口，等更多源到齐再挑，避免选到同名第一季。 */
        const val AUTO_PLAY_SETTLE_MS = 1_200L

        /** media3 的自定义暂停原因：非 NONE 即视为「用户手动暂停」。 */
        const val PAUSE_STOP_REASON = 1

        /** 追番卡片状态多久重探一次：源的集数几小时才变一次，探太勤只是白打站点。 */
        const val WATCH_STATUS_TTL_MS = 30 * 60 * 1000L

        /** 一次最多探几部，避免一口气把站点打成筛子。 */
        const val MAX_WATCH_PROBES = 4
    }

    var state by mutableStateOf(UiState())
        private set

    private val cookieStore = CookieStore(app)
    /** 已经尝试补过封面的番剧，避免每次刷新下载列表都重试网络。 */
    private val coverRetry = mutableSetOf<Int>()
    private val alertStore = AlertStore(app)
    private val aniList = AniListClient(app)
    private val engine = RuleEngine(
        HttpUrlConnectionClient(
            connectTimeoutMs = 6_000,
            readTimeoutMs = 8_000,
            cookieFor = { url -> cookieStore.cookieFor(url) },
        )
    )
    private val ruleFile = File(app.filesDir, "rules.ndjson")
    private val searchPrefs = app.getSharedPreferences("search", android.content.Context.MODE_PRIVATE)
    private val uiPrefs = app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    private val catalog = CatalogRepository(app)
    private val watchStore = WatchStore(app)
    private val danmakuRepo = DanmakuRepository(app)

    /** 已经加载过弹幕的 `subjectId-episode`，避免同一集重复请求。 */
    private var danmakuLoadedKey = ""

    /** 自动播放管线：armed 表示当前这次播放允许自动找源/换源。 */
    private var autoPlayArmed = false
    private var attempting = false
    private var autoPlayJob: kotlinx.coroutines.Job? = null
    private var onlineJob: kotlinx.coroutines.Job? = null
    /** 选集弹层正在等集数：每个源到货就试一次，拿到线路就停。 */
    private var pendingPicker = false
    /** 所有候选源都缺这一集时，记下最接近的缺口用于提示。 */
    private var gateCandidate: EpisodeGate? = null
    private val triedSources = mutableSetOf<String>()

    /** 下载进度变化时把队列刷进界面状态。 */
    private val downloadListener = object : androidx.media3.exoplayer.offline.DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: androidx.media3.exoplayer.offline.DownloadManager,
            download: androidx.media3.exoplayer.offline.Download,
            finalException: Exception?,
        ) {
            refreshDownloads()
            val meta = DownloadUtil.decode(download.request)
            val label = "${meta.subjectTitle} ${meta.episodeName}".trim()
            when (download.state) {
                androidx.media3.exoplayer.offline.Download.STATE_COMPLETED ->
                    appendLog("下载完成：$label")

                androidx.media3.exoplayer.offline.Download.STATE_FAILED ->
                    state = state.copy(downloadTip = "下载失败：$label（${failureText(download.failureReason)}）")

                else -> Unit
            }
        }

        override fun onDownloadsPausedChanged(
            downloadManager: androidx.media3.exoplayer.offline.DownloadManager,
            downloadsPaused: Boolean,
        ) {
            refreshDownloads()
        }
    }

    init {
        loadRules()
        state = state.copy(
            indexBaseUrl = catalog.baseUrl,
            imageBase = catalog.imageBase,
            themeMode = uiPrefs.getString("theme", "light") ?: "light",
            danmakuOn = uiPrefs.getBoolean("danmaku_on", true),
            danmakuAlpha = uiPrefs.getFloat("danmaku_alpha", 0.92f),
            danmakuScale = uiPrefs.getFloat("danmaku_scale", 1f),
            danmakuArea = uiPrefs.getFloat("danmaku_area", 0.62f),
            danmakuSpeed = uiPrefs.getFloat("danmaku_speed", 1f),
            danmakuOffsetMs = uiPrefs.getLong("danmaku_offset", 0L),
            danmakuOrigins = (uiPrefs.getString("danmaku_origins", "") ?: "")
                .split(',')
                .mapNotNull { name -> DanmakuOrigin.entries.firstOrNull { it.name == name } }
                .toSet()
                .ifEmpty { setOf(DanmakuOrigin.Catalog) },
            danmakuPickable = danmakuRepo.availableOrigins,
            alerts = alertStore.load(),
            searchHistory = searchPrefs.getString("history", null)
                ?.split('\u0001')?.filter { it.isNotBlank() }.orEmpty(),
            watches = watchStore.load().sortedByDescending { it.updatedAt },
        )
        loadHome()
        // 下载：缓存与队列必须在播放前就绪（播放器读的缓存和下载器写的是同一个）
        runCatching {
            DownloadUtil.setup(getApplication())
            DownloadUtil.manager.addListener(downloadListener)
        }
        refreshDownloads()
    }

    // ---------- 索引 ----------

    /** 索引基址可配置：默认 api.bgm.tv，被污染时换成可达的反代/镜像。 */
    fun updateIndexBase(url: String) {
        val normalized = url.trim().trimEnd('/')
        if (normalized.isEmpty() || !normalized.startsWith("http")) {
            showError("索引地址需要以 http(s):// 开头")
            return
        }
        catalog.baseUrl = normalized
        state = state.copy(indexBaseUrl = normalized, error = null)
        appendLog("索引地址已更新：$normalized")
    }

    fun refreshIndex() = loadHome(force = true)

    /** 封面前缀：把 lain.bgm.tv 换成可达的反代（如 http://host/img），留空则直连。 */
    fun updateImageBase(prefix: String) {
        val normalized = prefix.trim().trimEnd('/')
        if (normalized.isNotEmpty() && !normalized.startsWith("http")) {
            showError("封面地址需要以 http(s):// 开头，或留空")
            return
        }
        catalog.imageBase = normalized
        state = state.copy(imageBase = normalized, error = null)
        appendLog(
            if (normalized.isEmpty()) "封面地址已清空（直连 lain.bgm.tv）"
            else "封面地址已更新：$normalized"
        )
    }

    /** 只测连通性，不动缓存；用来分辨是 DNS/超时/HTTP/格式问题。 */
    fun testIndexConnection() {
        if (state.indexProbe == "测试中…") return
        state = state.copy(indexProbe = "测试中…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { catalog.probe() } }
            state = state.copy(
                indexProbe = result.fold(
                    onSuccess = { it },
                    onFailure = { "失败：${describe(it)}" },
                )
            )
        }
    }

    /** 从剪贴板导入日历 JSON，绕开网络直接落盘。 */
    fun importIndexJson(text: String) {
        val days = runCatching { catalog.importFromText(text) }.getOrElse {
            showError("索引 JSON 解析失败: ${it.message?.take(60)}")
            return
        }
        state = state.copy(
            home = HomeState(
                loading = false,
                days = days,
                updatedAt = System.currentTimeMillis(),
                error = null,
            ),
            error = null,
        )
        appendLog("索引已导入：${days.sumOf { it.items.size }} 部")
    }

    /**
     * 先读缓存立刻渲染，再按需刷新（6 小时 TTL）。
     * 首页永远不该因为网络问题白屏。
     */
    fun loadHome(force: Boolean = false) {
        val cached = catalog.cached()
        if (cached != null) {
            state = state.copy(home = state.home.copy(days = cached, updatedAt = catalog.cachedAt()))
        }

        val fresh = cached != null && System.currentTimeMillis() - catalog.cachedAt() < 6 * 3600_000L
        if (!force && fresh) return

        state = state.copy(home = state.home.copy(loading = true, error = null))
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { catalog.refresh() } }
            result.onSuccess { days ->
                state = state.copy(
                    home = HomeState(
                        loading = false,
                        days = days,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                appendLog("索引已更新：${days.sumOf { it.items.size }} 部当季番剧")
            }.onFailure { error ->
                state = state.copy(
                    home = state.home.copy(loading = false, error = describe(error))
                )
            }
        }
    }

    fun goHome() {
        state = state.copy(screen = Screen.Home)
    }

    fun goSearch() {
        state = state.copy(screen = Screen.Search, navForward = true)
    }

    fun goMine() {
        state = state.copy(screen = Screen.Mine, navForward = true)
    }

    fun openSubject(subject: CatalogSubject) {
        state = state.copy(
            screen = Screen.Detail,
            navForward = true,
            detail = subject,
            detailOrigin = state.screen,
            // 详情页自己也要能被返回：记下"是从哪一屏进来的"，
            // 否则 首页→详情→播放→返回 会直接跳回首页，把详情页吃掉。
            detailStack = state.detailStack + state.screen,
            hits = emptyList(),
            roads = emptyList(),
            error = null,
            detailInfo = null,
            airing = null,
            characters = emptyList(),
            playSubjectId = subject.id,
            entryRuleName = "",
            entrySrc = "",
            entryName = "",
        )
        loadDetailInfo(subject.id)
    }

    /** 详情页的富信息（简介全文 / 标签 / 集数 / 评分人数）。 */
    fun loadDetailInfo(id: Int) {
        state = state.copy(detailLoading = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { catalog.subjectDetail(id) } }
            result.onSuccess { detail ->
                state = state.copy(detailInfo = detail, detailLoading = false)
                mergeWatchMeta(detail)
                state.detail?.let { loadAiring(it) }
            }.onFailure {
                state = state.copy(detailLoading = false)
                appendLog("详情加载失败：${describe(it)}")
            }
        }
        // 角色 / 声优单独拿，失败了不影响详情主体
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { catalog.characters(id) }.getOrDefault(emptyList<SubjectCharacter>())
            }
            if (state.detail?.id == id) {
                state = state.copy(characters = list)
                if (list.isNotEmpty()) appendLog("角色 ${list.size} 位")
            }
        }
    }

    // ---------- 源体检 ----------

    // ---------- 季度番剧表 ----------

    private fun currentSeason(): Pair<Int, Int> {
        val now = java.time.LocalDate.now()
        return now.year to (((now.monthValue - 1) / 3) + 1)
    }

    /** 季度 -> [起, 止) 的日期区间。 */
    private fun seasonRange(year: Int, quarter: Int): Pair<String, String> {
        val startMonth = (quarter - 1) * 3 + 1
        val start = java.time.LocalDate.of(year, startMonth, 1)
        return start.toString() to start.plusMonths(3).toString()
    }

    /** 直接跳到某个季度（季度选择器用）。 */
    fun jumpSeason(year: Int, quarter: Int) {
        if (year <= 0 || quarter !in 1..4) return
        state = state.copy(
            seasonYear = year,
            seasonQuarter = quarter,
            seasonItems = emptyList(),
            seasonTotal = 0,
            navForward = true,
        )
        loadSeason(reset = true)
    }
    fun openSeason() {
        val (y, q) = if (state.seasonYear > 0) {
            state.seasonYear to state.seasonQuarter
        } else {
            currentSeason()
        }
        state = state.copy(
            screen = Screen.Season,
            navForward = true,
            navStack = state.navStack + state.screen,
            seasonYear = y,
            seasonQuarter = q,
            seasonItems = emptyList(),
            seasonTotal = 0,
        )
        loadSeason(reset = true)
    }

    /** ◀ ▶ 切季度。 */
    fun shiftSeason(delta: Int) {
        var y = state.seasonYear
        var q = state.seasonQuarter + delta
        if (q > 4) {
            q = 1
            y += 1
        } else if (q < 1) {
            q = 4
            y -= 1
        }
        state = state.copy(
            seasonYear = y,
            seasonQuarter = q,
            seasonItems = emptyList(),
            seasonTotal = 0,
        )
        loadSeason(reset = true)
    }

    /** 拉取季度表；reset=false 时按当前已加载数量翻页。 */
    fun loadSeason(reset: Boolean = false) {
        if (state.seasonLoading) return
        val year = state.seasonYear
        val quarter = state.seasonQuarter
        if (year == 0 || quarter == 0) return
        val offset = if (reset) 0 else state.seasonItems.size
        if (!reset && state.seasonTotal > 0 && offset >= state.seasonTotal) return

        state = state.copy(seasonLoading = true, seasonError = null)
        viewModelScope.launch {
            val (start, end) = seasonRange(year, quarter)
            val result = withContext(Dispatchers.IO) {
                runCatching { catalog.searchSeason(start, end, offset) }
            }
            state = state.copy(seasonLoading = false)
            result.onSuccess { (list, total) ->
                if (state.seasonYear != year || state.seasonQuarter != quarter) return@onSuccess
                // 只在本页内排序后追加，别对已加载的整表重排：
                // 全局重排会把新页里日期更早的条目插到最前，LazyGrid 位置跟着跳。
                val pageSorted = list.sortedWith(
                    compareBy<CatalogSubject>({ it.airDate.isBlank() }, { it.airDate })
                )
                val merged = if (offset == 0) pageSorted else state.seasonItems + pageSorted
                state = state.copy(seasonItems = merged, seasonTotal = total)
                appendLog("${year} 第 ${quarter} 季：${merged.size}/$total 部")
            }.onFailure {
                if (state.seasonYear == year && state.seasonQuarter == quarter) {
                    state = state.copy(seasonError = describe(it))
                }
            }
        }
    }
    /** 主题切换：light / dark / system。 */
    fun setThemeMode(mode: String) {
        if (mode != "light" && mode != "dark" && mode != "system") return
        uiPrefs.edit().putString("theme", mode).apply()
        state = state.copy(themeMode = mode)
    }
    fun openHealth() {
        state = state.copy(
            screen = Screen.Health,
            navForward = true,
            navStack = state.navStack + state.screen,
            healthKeyword = state.keyword.ifBlank { state.playQuery },
            health = emptyList(),
        )
    }

    fun updateHealthKeyword(text: String) {
        state = state.copy(healthKeyword = text)
    }

    fun closeHealth() {
        state = state.copy(
            screen = state.navStack.lastOrNull() ?: Screen.Mine,
            navForward = false,
            navStack = state.navStack.dropLast(1),
        )
    }

    /**
     * 对全部规则跑一遍体检。
     *
     * 浅检只搜索：判断站点活着、能搜到东西（快，52 个源约 20 秒）；
     * 深检再取一次线路：只有真拿到可播放线路才算"能用"（慢一倍，但结果可信）。
     */
    fun runHealthCheck(deep: Boolean) {
        if (state.healthBusy) return
        val keyword = state.healthKeyword.trim().ifBlank { state.keyword.trim() }
        if (keyword.isBlank()) {
            showError("先在搜索页输入番名，或在这里填一个")
            return
        }
        val rules = state.rules
        if (rules.isEmpty()) {
            showError("还没有规则")
            return
        }

        state = state.copy(
            health = emptyList(),
            healthBusy = true,
            healthDeep = deep,
            healthKeyword = keyword,
        )
        appendLog("体检开始：${rules.size} 个源，${if (deep) "深检" else "浅检"}")

        viewModelScope.launch {
            val gate = Semaphore(4)
            val jobs = rules.map { rule ->
                launch {
                    gate.withPermit {
                        val started = System.currentTimeMillis()
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching { engine.search(rule, keyword) }
                        }
                        val elapsed = System.currentTimeMillis() - started
                        val health = outcome.fold(
                            onSuccess = { result ->
                                if (result.items.isEmpty()) {
                                    SourceHealth(rule.name, "empty", latencyMs = elapsed)
                                } else if (!deep) {
                                    SourceHealth(rule.name, "ok", result.items.size, 0, null, elapsed)
                                } else {
                                    val first = result.items.first()
                                    val chapters = withContext(Dispatchers.IO) {
                                        runCatching { engine.chapters(rule, first.src) }.getOrNull()
                                    }
                                    val usable = chapters?.roads?.count { it.urls.isNotEmpty() } ?: 0
                                    if (usable > 0) {
                                        SourceHealth(rule.name, "ok", result.items.size, usable, null, elapsed)
                                    } else {
                                        SourceHealth(
                                            rule.name, "error", result.items.size, 0,
                                            "有结果但取不到线路", elapsed,
                                        )
                                    }
                                }
                            },
                            onFailure = { error ->
                                SourceHealth(rule.name, "error", 0, 0, describe(error), elapsed)
                            },
                        )
                        state = state.copy(health = state.health + health)
                    }
                }
            }
            jobs.joinAll()
            state = state.copy(healthBusy = false)
            val ok = state.health.count { it.status == "ok" }
            appendLog("体检完成：$ok/${state.health.size} 个源可用")
        }
    }

    /** 体检报告的纯文本版，用系统分享导出。 */
    fun healthReportText(): String {
        val list = state.health
        if (list.isEmpty()) return "还没有体检结果"
        val ok = list.count { it.status == "ok" }
        val empty = list.count { it.status == "empty" }
        val failed = list.count { it.status == "error" }
        return buildString {
            appendLine("木漏 · 源体检")
            appendLine("关键词：${state.healthKeyword}")
            appendLine("模式：${if (state.healthDeep) "深检（搜索 + 取线路）" else "浅检（仅搜索）"}")
            appendLine("结果：可用 $ok · 空 $empty · 失败 $failed（共 ${list.size}）")
            appendLine()
            list.sortedBy { when (it.status) { "ok" -> 0; "empty" -> 1; else -> 2 } }.forEach { h ->
                append("- [${if (h.status == "ok") "可用" else if (h.status == "empty") "空" else "失败"}] ")
                append(h.rule)
                if (h.status == "ok") {
                    append("：${h.items} 条结果")
                    if (h.roads > 0) append("，${h.roads} 条线路")
                }
                h.error?.let { append("：$it") }
                append("（${h.latencyMs}ms）")
                appendLine()
            }
        }
    }

    /** 查「下一集什么时候播」：只对能匹配上 AniList 的番生效，失败就静默不显示。 */
    private fun loadAiring(subject: CatalogSubject) {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { aniList.nextAiring(subject) }.getOrNull()
            }
            if (state.detail?.id != subject.id) return@launch
            state = state.copy(airing = info)
            if (info != null) appendLog("下一集：第 ${info.episode} 集（AniList 匹配成功）")
        }
    }
    /** 追番 / 取消追番。 */
    fun toggleWatch() {
        val subject = state.detail ?: return
        val existing = state.watches.firstOrNull { it.subjectId == subject.id }
        val updated = if (existing != null) {
            state.watches.filterNot { it.subjectId == subject.id }
        } else {
            listOf(
                WatchEntry(
                    subjectId = subject.id,
                    title = subject.displayTitle,
                    cover = subject.coverUrlSmall,
                    totalEpisodes = state.detailInfo?.episodeCount ?: 0,
                    updatedAt = System.currentTimeMillis(),
                )
            ) + state.watches
        }
        state = state.copy(watches = updated.sortedByDescending { it.updatedAt })
        watchStore.save(state.watches)
        appendLog(if (existing != null) "取消追番：${subject.displayTitle}" else "已追番：${subject.displayTitle}")
    }

    /** 从索引详情补全追番记录的封面/集数。 */
    private fun mergeWatchMeta(detail: SubjectDetail) {
        val index = state.watches.indexOfFirst { it.subjectId == detail.id }
        if (index < 0) return
        val old = state.watches[index]
        if (old.title == detail.displayTitle &&
            old.cover == detail.coverUrlSmall &&
            old.totalEpisodes == detail.episodeCount
        ) return
        val updated = state.watches.toMutableList()
        updated[index] = old.copy(
            title = detail.displayTitle,
            cover = detail.coverUrlSmall,
            totalEpisodes = detail.episodeCount,
        )
        state = state.copy(watches = updated)
        watchStore.save(updated)
    }

    /** 播放器每 5 秒回报一次进度；没有关联索引条目时直接忽略。 */
    fun saveProgress(positionMs: Long, durationMs: Long) {
        val subjectId = state.playSubjectId
        if (subjectId == 0 || state.playEpisodeIndex < 0) return
        val existing = state.watches.firstOrNull { it.subjectId == subjectId }
        val base = existing ?: WatchEntry(
            subjectId = subjectId,
            title = state.title.substringBefore(" · "),
            // 首次记录就带上封面/集数，否则首页卡片会一直没有图
            cover = state.detail?.coverUrlSmall.orEmpty(),
            totalEpisodes = state.detailInfo?.episodeCount ?: 0,
        )
        val entry = base.copy(
            ruleName = state.entryRuleName,
            entrySrc = state.entrySrc,
            entryName = state.entryName,
            roadIndex = state.playRoadIndex,
            episodeIndex = state.playEpisodeIndex,
            positionMs = positionMs,
            durationMs = durationMs,
            totalEpisodes = base.totalEpisodes.takeIf { it > 0 } ?: (state.detailInfo?.episodeCount ?: 0),
            updatedAt = System.currentTimeMillis(),
        )
        val updated = state.watches.filterNot { it.subjectId == subjectId } + entry
        state = state.copy(watches = updated.sortedByDescending { it.updatedAt })
        watchStore.save(state.watches)
    }

    /**
     * 探一遍追番的"源更新到第几集"，结果写回追番记录，首页卡片据此显示状态。
     *
     * 用的是「更新提醒」那套判据——拿记住的规则去拉分集，数最长的线路有几集。
     * 失败就记 0：界面什么都不显示，宁可不说也不瞎说。
     */
    fun refreshWatchStatus(force: Boolean = false) {
        if (state.watchProbing) return
        val now = System.currentTimeMillis()
        val stale = state.watches.filter { entry ->
            entry.hasProgress && (
                force || entry.sourceCheckedAt == 0L ||
                    now - entry.sourceCheckedAt > WATCH_STATUS_TTL_MS
                )
        }
        if (stale.isEmpty()) return
        state = state.copy(watchProbing = true)
        viewModelScope.launch {
            var probed = 0
            for (entry in stale.take(MAX_WATCH_PROBES)) {
                val rule = state.rules.firstOrNull { it.name == entry.ruleName } ?: continue
                if (rule.baseUrl.isBlank()) continue
                val max = withContext(Dispatchers.IO) {
                    runCatching {
                        engine.chapters(rule, entry.entrySrc)
                            .roads.filter { it.urls.isNotEmpty() }
                            .maxOfOrNull { it.urls.size } ?: 0
                    }.getOrDefault(0)
                }
                val index = state.watches.indexOfFirst { it.subjectId == entry.subjectId }
                if (index >= 0) {
                    val updated = state.watches.toMutableList()
                    updated[index] = updated[index].copy(
                        sourceMax = max,
                        sourceCheckedAt = System.currentTimeMillis(),
                    )
                    state = state.copy(watches = updated)
                    watchStore.save(updated)
                    probed++
                }
            }
            state = state.copy(watchProbing = false)
            if (probed > 0) appendLog("追番状态已更新：$probed 部")
        }
    }

    /** 首页「继续观看」：恢复线路后直接跳到上次的集数与进度。 */
    fun resumeWatch(entry: WatchEntry) {
        // 只有"追了但还没看过"的条目，进详情页而不是报错
        if (!entry.hasProgress) {
            openSubject(
                CatalogSubject(
                    id = entry.subjectId,
                    name = entry.title,
                    nameCn = entry.title,
                    images = Images(common = entry.cover),
                )
            )
            return
        }
        val rule = state.rules.firstOrNull { it.name == entry.ruleName }
        if (rule == null) {
            // 规则没了（被删/导入失败）：回落到自动找源，而不是让用户看到报错
            appendLog("规则「${entry.ruleName}」已不存在，改为自动找源")
            startPlayback(
                state.detail?.takeIf { it.id == entry.subjectId }
                    ?: CatalogSubject(
                        id = entry.subjectId,
                        name = entry.title,
                        nameCn = entry.title,
                        images = Images(common = entry.cover),
                    )
            )
            return
        }

        // 与"搜索进入"走同一套播放管线：直接起播保存的源，同时后台补全候选列表，
        // 这样源切换条和自动换源对两条入口都成立。
        val subject = state.detail?.takeIf { it.id == entry.subjectId }
            ?: CatalogSubject(
                id = entry.subjectId,
                name = entry.title,
                nameCn = entry.title,
                images = Images(common = entry.cover),
            )

        autoPlayArmed = true
        attempting = true // 主线路起播期间，后台搜索的结果不许抢跑
        triedSources.clear()
        state = state.copy(
            screen = Screen.Player,
            playerBackTo = Screen.Home,
            detail = subject,
            playSubjectId = entry.subjectId,
            title = entry.title,
            playQuery = entry.title,
            keyword = entry.title,
            searchMode = SearchMode.Sources,
            playPhase = PlayPhase.Starting,
            playMedia = null,
            playPageUrl = "",
            playStartMs = 0,
            playEpisodeIndex = -1,
            episodeNumber = entry.episodeIndex + 1,
            failedSources = emptySet(),
            autoSwitches = 0,
            hits = emptyList(),
            completed = 0,
            total = 0,
            error = null,
            entryRuleName = rule.name,
            entrySrc = entry.entrySrc,
            entryName = entry.entryName,
        )

        // 后台补源：只填 hits，不自动起播（已经在起播保存的源了）
        if (state.detailInfo?.id != entry.subjectId) loadDetailInfo(entry.subjectId)
        search(entry.title)

        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { engine.chapters(rule, entry.entrySrc) }
            }
            outcome.onSuccess { result ->
                val roads = result.roads.filter { it.urls.isNotEmpty() }
                if (roads.isEmpty()) {
                    candidateFailed("${rule.name}：没有可用线路")
                    return@onSuccess
                }
                val road = roads.getOrNull(entry.roadIndex) ?: roads.first()
                state = state.copy(roads = roads)
                play(road, entry.episodeIndex.coerceIn(0, (road.urls.size - 1).coerceAtLeast(0)))
                attempting = false
            }.onFailure { error ->
                candidateFailed("${rule.name}：${describe(error)}")
            }
        }
    }

    /** 详情页里"找片源"：用索引标题（中文优先）去并发搜所有源。 */
    fun searchFromDetail() {
        val subject = state.detail ?: return
        state = state.copy(selected = null, keyword = subject.displayTitle)
        search(subject.displayTitle)
    }

    // ---------- 播放管线（索引点进去 → 自动找源 → 失败换源） ----------

    /** 记住搜过的词，最多 10 条，搜索页空态时展示。 */
    fun rememberKeyword(keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        val history = (listOf(k) + state.searchHistory.filter { it != k }).take(10)
        searchPrefs.edit().putString("history", history.joinToString("\u0001")).apply()
        state = state.copy(searchHistory = history)
    }

    fun clearSearchHistory() {
        searchPrefs.edit().remove("history").apply()
        state = state.copy(searchHistory = emptyList())
    }

    /** 搜索页：默认只显示有结果的源，失败/空结果折叠。 */
    fun toggleFailedSources() {
        state = state.copy(showFailedSources = !state.showFailedSources)
    }
    fun updateKeyword(text: String) {
        state = state.copy(keyword = text, searchMode = SearchMode.Index)
        onlineJob?.cancel()
        if (text.length < 2) {
            state = state.copy(onlineResults = emptyList(), onlineSearching = false)
            return
        }
        // 输入停顿 400ms 再打在线接口，避免每敲一个字都发一次请求
        onlineJob = viewModelScope.launch {
            delay(400)
            state = state.copy(onlineSearching = true)
            val result = withContext(Dispatchers.IO) { runCatching { catalog.searchSubjects(text) } }
            state = state.copy(onlineSearching = false)
            result
                .onSuccess { state = state.copy(onlineResults = it) }
                .onFailure { appendLog("在线索引搜索失败：${describe(it)}") }
        }
    }

    /**
     * 点索引条目直接进入播放：先到播放页，再并发找源，第一个可用的立刻起播。
     *
     * 用户不需要在搜索页挑源——源的选择被推迟到"播不动"的时候。
     */
    fun startPlayback(subject: CatalogSubject) {
        rememberKeyword(subject.displayTitle)
        autoPlayArmed = true
        attempting = false
        gateCandidate = null
        triedSources.clear()
        state = state.copy(
            screen = Screen.Player,
            // 从详情开播 → 返回目标就是详情页；死循环由 detailStack 兜住（详情返回时弹栈）。
            // 已经在播放页里换源/换集时保持原返回目标，否则会自己指向自己。
            playerBackTo = if (state.screen == Screen.Player) state.playerBackTo else state.screen,
            detail = subject,
            playSubjectId = subject.id,
            title = subject.displayTitle,
            keyword = subject.displayTitle,
            searchMode = SearchMode.Sources,
            playPhase = PlayPhase.Searching,
            playQuery = subject.displayTitle,
            playMedia = null,
            playPageUrl = "",
            playStartMs = 0,
            playEpisodeIndex = -1,
            episodeNumber = 1,
            failedSources = emptySet(),
            autoSwitches = 0,
            hits = emptyList(),
            completed = 0,
            total = 0,
            error = null,
        )
        // 播放页信息卡要用到评分/更新集数，这里补齐（已缓存过就不重复请求）
        if (state.detailInfo?.id != subject.id) loadDetailInfo(subject.id)
        search(subject.displayTitle)
    }

    /** 从详情页的「播放」按钮进入同一条管线。 */
    fun playCurrentSubject() {
        val subject = state.detail ?: return
        startPlayback(subject)
    }

    // ---------- 更新提醒 ----------

    /** 给"源还没更新"的那一集设提醒：之后定时去该站点探测，出现了就通知。 */
    fun addEpisodeAlert() {
        val gate = state.episodeGate
        if (gate == null) {
            showError("先选一集")
            return
        }
        val rule = gate.rule
        if (rule == null || gate.entrySrc.isBlank()) {
            showError("还没有可用的源，无法设提醒")
            return
        }
        val id = System.currentTimeMillis()
        val alert = EpisodeAlert(
            id = id,
            subjectId = state.playSubjectId,
            title = state.detail?.displayTitle ?: state.title.substringBefore(" · "),
            cover = state.detail?.coverUrlSmall.orEmpty(),
            episode = gate.requested,
            ruleName = gate.ruleName,
            entrySrc = gate.entrySrc,
            createdAt = id,
        )
        val list = state.alerts
            .filterNot { it.subjectId == alert.subjectId && it.episode == alert.episode } + alert
        alertStore.save(list)
        state = state.copy(alerts = list)
        appendLog("已设置提醒：第 ${alert.episode} 集（源出现后通知）")
    }

    fun removeAlert(id: Long) {
        val list = state.alerts.filterNot { it.id == id }
        alertStore.save(list)
        state = state.copy(alerts = list)
    }

    /** 点通知进来：直接按那一集开播。 */
    fun openAlert(id: Long): Boolean {
        val alert = state.alerts.firstOrNull { it.id == id } ?: return false
        val subject = CatalogSubject(
            id = alert.subjectId,
            name = alert.title,
            nameCn = alert.title,
            images = Images(common = alert.cover),
        )
        startPlayback(subject)
        state = state.copy(episodeNumber = alert.episode)
        return true
    }
    /** 源没更新到目标集时：「先看能看的最新一集」。 */
    fun watchLatestAvailable() {
        val gate = state.episodeGate ?: return
        val rule = gate.rule ?: return
        if (gate.sourceMax <= 0) return
        state = state.copy(episodeNumber = gate.sourceMax, episodeGate = null, error = null)
        playRoad(rule, SearchItem(gate.entryName, gate.entrySrc), 0)
    }

    /** 选集里点到"源未到/未播出"的集数：只展示提示，不切屏。 */
    fun showGateFor(number: Int) {
        val sourceMax = state.roads.getOrNull(state.playRoadIndex)?.urls?.size ?: 0
        val expected = officialAired()
        val rule = state.rules.firstOrNull { it.name == state.entryRuleName }
        state = state.copy(
            episodeGate = EpisodeGate(
                reason = if (expected > 0 && number > expected) GateReason.NotAired else GateReason.SourceBehind,
                requested = number,
                expected = expected,
                sourceMax = sourceMax,
                ruleName = state.entryRuleName,
                entrySrc = state.entrySrc,
                entryName = state.entryName,
                rule = rule,
            ),
            episodeNumber = number,
        )
    }

    fun dismissGate() {
        state = state.copy(episodeGate = null)
    }
    /** 播完自动下一集；已经是最后一集就提示一下。 */
    fun playNextEpisode() {
        val road = state.roads.getOrNull(state.playRoadIndex) ?: return
        val next = state.playEpisodeIndex + 1
        if (next !in road.urls.indices) {
            appendLog("已经是最后一集")
            return
        }
        appendLog("自动连播：第 ${next + 1} 集")
        play(road, next)
    }
    /** 详情页的「选集」：弹集数面板；还没找到源就先自动找。 */
    fun openEpisodeSheet() {
        if (state.roads.isNotEmpty()) {
            state = state.copy(episodeSheet = true, episodeLoading = false)
            return
        }
        state = state.copy(episodeSheet = true, episodeLoading = true)
        val subject = state.detail ?: return
        pendingPicker = true
        val existing = candidates().firstOrNull()
        if (existing != null) {
            fillPicker(existing.first, existing.second)
        } else if (state.hits.isEmpty()) {
            searchSources(subject.displayTitle)
        }
    }

    fun closeEpisodeSheet() {
        pendingPicker = false
        state = state.copy(episodeSheet = false, episodeLoading = false)
    }

    /** 弹层里点某一集：关掉弹层直接播。 */
    fun pickEpisode(road: Road, index: Int) {
        state = state.copy(episodeSheet = false, episodeLoading = false)
        play(road, index)
    }

    // ---------- 人机验证（Cloudflare 挑战 / 验证码） ----------

    private fun searchUrlFor(rule: AnimeRule, keyword: String): String {
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
        val url = rule.searchUrl.replace("@keyword", encoded)
        return if (url.startsWith("http")) url
        else rule.baseUrl.trimEnd('/') + "/" + url.trimStart('/')
    }

    /** 打开验证页：加载刚才被拦的那个搜索地址，让 WebView 去过挑战/填验证码。 */
    fun startVerify(rule: AnimeRule) {
        val keyword = state.keyword.ifBlank { state.playQuery }
        state = state.copy(
            screen = Screen.Verify,
            navForward = true,
            // 从播放页的源面板也能进验证，返回必须回到播放页而不是搜索页
            navStack = state.navStack + state.screen,
            verifyUrl = searchUrlFor(rule, keyword),
            verifyRuleName = rule.name,
            verifyDone = false,
        )
    }

    fun onVerifyPageLoaded() {
        state = state.copy(verifyDone = true)
    }

    /** 页面加载完 → 读 Cookie 存下来 → 用同一条规则重试一次。 */
    fun finishVerify() {
        val url = state.verifyUrl
        if (url.isNotBlank()) {
            val cookie = runCatching {
                android.webkit.CookieManager.getInstance().getCookie(url)
            }.getOrNull()
            cookieStore.save(url, cookie)
            appendLog("验证完成：${runCatching { java.net.URI(url).host }.getOrNull()} cookie=${cookie?.take(24).orEmpty()}")
        }
        val rule = state.rules.firstOrNull { it.name == state.verifyRuleName }
        val back = state.navStack.lastOrNull() ?: Screen.Search
        state = state.copy(
            screen = back,
            navForward = false,
            navStack = state.navStack.dropLast(1),
            verifyUrl = "",
            verifyRuleName = "",
            verifyDone = false,
            searchMode = if (back == Screen.Search) SearchMode.Sources else state.searchMode,
        )
        if (rule != null) retryRule(rule)
    }

    fun cancelVerify() {
        val back = state.navStack.lastOrNull() ?: Screen.Search
        state = state.copy(
            screen = back,
            navForward = false,
            navStack = state.navStack.dropLast(1),
            verifyUrl = "",
            verifyRuleName = "",
            verifyDone = false,
        )
    }

    /** 只重试这一条规则，不动其它源的结果。 */
    private fun retryRule(rule: AnimeRule) {
        val keyword = state.keyword.ifBlank { state.playQuery }
        if (keyword.isBlank()) return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { engine.search(rule, keyword) } }
            val hit = outcome.fold(
                onSuccess = { result ->
                    SourceHit(rule = rule, ruleName = rule.name, items = result.items, done = true)
                },
                onFailure = { error ->
                    SourceHit(rule = rule, ruleName = rule.name, error = describe(error), done = true)
                },
            )
            state = state.copy(hits = state.hits.map { if (it.ruleName == hit.ruleName) hit else it })
            appendLog("重试 ${rule.name}：${hit.items.size} 条${hit.error?.let { "（$it）" }.orEmpty()}")
        }
    }

    /** 当前还能尝试的候选（先按标题相似度，再按到达顺序：先到的先播）。 */
    private fun candidates(): List<Pair<AnimeRule, SearchItem>> {
        val query = state.playQuery.ifBlank { state.keyword }
        // 记忆播放源：上一集/上次看这部用的源，排在最前面
        val preferred = state.watches
            .firstOrNull { it.subjectId == state.playSubjectId }
            ?.ruleName
            .orEmpty()

        data class Scored(val rule: AnimeRule, val item: SearchItem, val score: Int)

        val scored = ArrayList<Scored>()
        for (hit in state.hits) {
            if (hit.error != null) continue
            for (item in hit.items) {
                val score = titleScore(query, item.name)
                // 负分 = 判定为不同作品（季数/剧场版标记对不上），直接丢掉：
                // 宁可"没找到"，也不要播成别的番。
                if (score >= 0) scored.add(Scored(hit.rule, item, score))
            }
        }
        return scored
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenBy { if (it.rule.name == preferred) 0 else 1 }
            )
            .map { it.rule to it.item }
    }

    /**
     * 作品版本标记：restart / 第二季 / 剧场版 这些一旦对不上，就不是同一部番。
     *
     * 实测踩过的坑：搜「魔法少女育成计划 restart」，源站同时有 2016 年的
     * 「魔法少女育成计划」（12 集已完结），纯相似度算法会选中老版。
     */
    private fun versionTokens(text: String): Set<String> {
        val t = text.lowercase()
        val tokens = mutableSetOf<String>()
        listOf(
            "restart", "剧场版", "劇場版", "特别篇", "特別篇", "总集篇", "番外",
            "final season", "season 2", "season 3", "season 4",
            "2nd season", "3rd season", "4th season",
        ).forEach { if (t.contains(it)) tokens += it }
        Regex("第\\s*([2-9]|[一二三四五六七八九])\\s*[期季部]").findAll(t).forEach {
            tokens += it.value.replace(" ", "")
        }
        Regex("\\b(ii|iii|iv)\\b").findAll(t).forEach { tokens += it.value }
        return tokens
    }

    /**
     * 标题相似度。规则：
     * 1. 归一化后完全相等 → 最高分；
     * 2. **版本标记必须双向一致**：query 有 restart 而候选没有（或反过来）→ 直接判负，
     *    避免"同系列不同季"互相顶替；
     * 3. 其余按前缀 / 包含 / 长度差打分。
     */
    private fun titleScore(query: String, candidate: String): Int {
        val a = query.replace(" ", "").lowercase()
        val b = candidate.replace(" ", "").lowercase()
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b) return 1000

        val qaTokens = versionTokens(query)
        val cbTokens = versionTokens(candidate)
        if (qaTokens != cbTokens) return -1000

        var prefix = 0
        while (prefix < a.length && prefix < b.length && a[prefix] == b[prefix]) prefix++
        val contain = if (a.contains(b) || b.contains(a)) 200 else 0
        val lengthPenalty = kotlin.math.abs(a.length - b.length) * 3
        return prefix * 2 + contain + minOf(a.length, b.length) - lengthPenalty
    }

    /** 播放页下拉刷新：重跑一遍源搜索，不打扰当前播放。 */
    fun refreshSources() {
        val query = state.playQuery.ifBlank { state.keyword }
        if (query.isBlank() || state.busy) return
        autoPlayArmed = false
        triedSources.clear()
        appendLog("手动刷新源")
        search(query)
    }

    private fun sourceKey(rule: AnimeRule, item: SearchItem): String = "${rule.name}|${item.src}"

    /** 有一个源返回结果就尝试起播；多路并发时只允许一个 attempt 在飞。 */
    private fun maybeAutoPlay() {
        // 选集弹层优先：只取线路，不切屏、不起播
        if (pendingPicker) {
            maybeFillPicker()
            return
        }
        if (!autoPlayArmed || attempting || state.playPhase != PlayPhase.Searching) return
        if (autoPlayJob?.isActive == true) return
        autoPlayJob = viewModelScope.launch {
            // 收敛窗口：第一个源返回时不立刻播，等 1.2 秒让更多源到齐，
            // 否则会拿"只有半张候选表"的状态去选，容易选到同名第一季。
            if (state.busy) delay(AUTO_PLAY_SETTLE_MS)
            if (!autoPlayArmed || attempting || state.playPhase != PlayPhase.Searching) return@launch
            val next = candidates().firstOrNull { (rule, item) -> sourceKey(rule, item) !in triedSources }
                ?: return@launch
            attemptPlay(next.first, next.second)
        }
    }

    /** 选集弹层：挑一个没试过的源去取线路（不起播）。 */
    private fun maybeFillPicker() {
        if (!pendingPicker || state.roads.isNotEmpty()) return
        val next = candidates().firstOrNull { (rule, item) -> sourceKey(rule, item) !in triedSources }
            ?: return
        fillPicker(next.first, next.second)
    }

    private fun fillPicker(rule: AnimeRule, item: SearchItem) {
        val key = sourceKey(rule, item)
        triedSources += key
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) { runCatching { engine.chapters(rule, item.src) } }
            val elapsed = System.currentTimeMillis() - started
            result.onSuccess { outcome ->
                val roads = outcome.roads.filter { it.urls.isNotEmpty() }
                if (roads.isEmpty()) {
                    markStatus(key, SourceStatus(error = "没有可用线路", latencyMs = elapsed))
                    maybeFillPicker()
                    return@onSuccess
                }
                markStatus(key, SourceStatus(roads = roads, latencyMs = elapsed))
                pendingPicker = false
                state = state.copy(
                    roads = roads,
                    entryRuleName = rule.name,
                    entrySrc = item.src,
                    entryName = item.name,
                    episodeLoading = false,
                    episodeSheet = true,
                )
            }.onFailure { error ->
                markStatus(key, SourceStatus(error = describe(error), latencyMs = elapsed))
                maybeFillPicker()
            }
        }
    }
    private fun attemptPlay(rule: AnimeRule, item: SearchItem) {        attempting = true
        val key = sourceKey(rule, item)
        triedSources += key
        state = state.copy(
            screen = Screen.Player,
            playPhase = PlayPhase.Starting,
            entryRuleName = rule.name,
            entrySrc = item.src,
            entryName = item.name,
            error = null,
            sourceStatus = state.sourceStatus + (key to SourceStatus(probing = true)),
        )
        appendLog("尝试源 ${rule.name}：${item.name}")
        val started = System.currentTimeMillis()

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { engine.chapters(rule, item.src) }
            }
            val elapsed = System.currentTimeMillis() - started
            result.onSuccess { outcome ->
                val roads = outcome.roads.filter { it.urls.isNotEmpty() }
                if (roads.isEmpty()) {
                    markStatus(key, SourceStatus(error = "没有可用线路", latencyMs = elapsed))
                    candidateFailed("${rule.name}：没有可用线路")
                    return@onSuccess
                }
                markStatus(key, SourceStatus(roads = roads, latencyMs = elapsed))
                val road = roads.first()
                val index = matchEpisodeIndex(road, state.episodeNumber)
                if (index == null) {
                    // 源里没有这一集：记下"最好情况"，继续换下一个源
                    noteGate(rule, item, road, roads)
                    candidateFailed("${rule.name}：源只到第 ${road.urls.size} 集")
                    return@onSuccess
                }
                state = state.copy(roads = roads, title = item.name)
                play(road, index)
                attempting = false
            }.onFailure { error ->
                markStatus(key, SourceStatus(error = describe(error), latencyMs = elapsed))
                candidateFailed("${rule.name}：${describe(error)}")
            }
        }
    }

    private fun markStatus(key: String, status: SourceStatus) {
        state = state.copy(sourceStatus = state.sourceStatus + (key to status))
    }

    /** 面板里展开某个源时按需探测线路，不自动起播。 */
    fun probeSource(rule: AnimeRule, item: SearchItem) {
        val key = sourceKey(rule, item)
        val existing = state.sourceStatus[key]
        if (existing?.probing == true || existing?.roads?.isNotEmpty() == true) return
        markStatus(key, SourceStatus(probing = true))
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) { runCatching { engine.chapters(rule, item.src) } }
            val elapsed = System.currentTimeMillis() - started
            result.onSuccess { outcome ->
                val roads = outcome.roads.filter { it.urls.isNotEmpty() }
                markStatus(
                    key,
                    if (roads.isEmpty()) SourceStatus(error = "没有可用线路", latencyMs = elapsed)
                    else SourceStatus(roads = roads, latencyMs = elapsed),
                )
            }.onFailure { markStatus(key, SourceStatus(error = describe(it), latencyMs = elapsed)) }
        }
    }

    /** 切到指定源的指定线路，保持当前集数。 */
    fun playRoad(rule: AnimeRule, item: SearchItem, roadIndex: Int) {
        autoPlayArmed = true
        attempting = false
        val key = sourceKey(rule, item)
        val cached = state.sourceStatus[key]?.roads.orEmpty()

        fun start(roads: List<Road>) {
            val road = roads.getOrNull(roadIndex) ?: roads.first()
            state = state.copy(
                roads = roads,
                entryRuleName = rule.name,
                entrySrc = item.src,
                entryName = item.name,
                failedSources = emptySet(),
                autoSwitches = 0,
                error = null,
            )
            val index = matchEpisodeIndex(road, state.episodeNumber)
            if (index == null) {
                state = state.copy(
                    episodeGate = buildGate(rule, item, road.urls.size),
                    playPhase = PlayPhase.Failed,
                    error = null,
                    playMedia = null,
                )
                return
            }
            play(road, index)
        }

        if (cached.isNotEmpty()) {
            start(cached)
            return
        }
        markStatus(key, SourceStatus(probing = true))
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) { runCatching { engine.chapters(rule, item.src) } }
            val elapsed = System.currentTimeMillis() - started
            result.onSuccess { outcome ->
                val roads = outcome.roads.filter { it.urls.isNotEmpty() }
                if (roads.isEmpty()) {
                    markStatus(key, SourceStatus(error = "没有可用线路", latencyMs = elapsed))
                } else {
                    markStatus(key, SourceStatus(roads = roads, latencyMs = elapsed))
                    start(roads)
                }
            }.onFailure { markStatus(key, SourceStatus(error = describe(it), latencyMs = elapsed)) }
        }
    }

    private fun candidateFailed(reason: String) {
        attempting = false
        appendLog("换源：$reason")
        state = state.copy(
            failedSources = state.failedSources + state.entryRuleName,
            playPhase = PlayPhase.Searching,
            playMedia = null,
            playPageUrl = "",
        )
        if (state.autoSwitches >= MAX_AUTO_SWITCH) {
            autoPlayArmed = false
            state = state.copy(
                screen = Screen.Player,
                playPhase = PlayPhase.Failed,
                error = if (gateCandidate == null) "已尝试多个源都失败：$reason" else null,
                episodeGate = gateCandidate,
            )
            return
        }
        state = state.copy(autoSwitches = state.autoSwitches + 1)
        maybeAutoPlay()
        // 没有候选了但搜索还在跑：等下一个源返回时由 search() 继续触发
        if (state.playPhase == PlayPhase.Searching && candidates().none {
                (rule, item) -> sourceKey(rule, item) !in triedSources
            } && !state.busy
        ) {
            autoPlayArmed = false
            state = state.copy(
                screen = Screen.Player,
                playPhase = PlayPhase.Failed,
                // 全部源都缺这一集 → 展示"源还没更新"，而不是笼统的失败
                error = if (gateCandidate == null) "没有可用的源：$reason" else null,
                episodeGate = gateCandidate,
            )
        }
    }

    /** 记录"最接近"的集数缺口：源最多到第几集，用于给用户提示。 */
    private fun noteGate(rule: AnimeRule, item: SearchItem, road: Road, roads: List<Road>) {
        val sourceMax = roads.maxOfOrNull { it.urls.size } ?: road.urls.size
        val previous = gateCandidate
        if (previous == null || sourceMax > previous.sourceMax) {
            gateCandidate = buildGate(rule, item, sourceMax)
        }
    }

    /** 官方进度：优先 eps（已播），没给就用总集数，都没有当未知（0）。 */
    private fun officialAired(): Int {
        val eps = state.detailInfo?.eps ?: 0
        if (eps > 0) return eps
        return state.detailInfo?.totalEpisodes ?: 0
    }

    private fun buildGate(rule: AnimeRule, item: SearchItem, sourceMax: Int): EpisodeGate {
        val requested = state.episodeNumber
        val expected = officialAired()
        return EpisodeGate(
            reason = if (expected > 0 && requested > expected) GateReason.NotAired else GateReason.SourceBehind,
            requested = requested,
            expected = expected,
            sourceMax = sourceMax,
            ruleName = rule.name,
            entrySrc = item.src,
            entryName = item.name,
            rule = rule,
        )
    }

    /** 播放器报错（CDN 连不上、编码不支持等）→ 自动换下一个源。 */
    fun onPlaybackError(message: String) {
        appendLog("播放出错：$message")
        // 记到源状态里，面板上会显示成 ✗ + 原因
        if (state.entryRuleName.isNotEmpty()) {
            val key = "${state.entryRuleName}|${state.entrySrc}"
            val prev = state.sourceStatus[key] ?: SourceStatus()
            markStatus(key, prev.copy(probing = false, error = message))
        }
        if (!autoPlayArmed) {
            state = state.copy(playPhase = PlayPhase.Failed, error = message, playMedia = null)
            return
        }
        candidateFailed(message)
    }

    /** 从播放页回详情看简介/标签，不清空已找到的源。 */
    /** 全屏播放：由界面切换横竖屏与系统栏，这里只记状态。 */
    fun setFullscreen(enabled: Boolean) {
        state = state.copy(playerFullscreen = enabled)
    }

    /** 播放过程中记录位置：从播放页跳详情再回来，能接着看而不是从头。 */
    fun updatePlayPosition(positionMs: Long) {
        if (positionMs > 0 && state.playMedia != null) {
            state = state.copy(playStartMs = positionMs)
        }
    }
    fun openDetailFromPlayer() {
        val subject = state.detail ?: return
        // 记下来源：从播放页进的详情，返回时要回播放页，而不是最初的首页/搜索
        state = state.copy(
            screen = Screen.Detail,
            detailOrigin = Screen.Player,
            detailStack = state.detailStack + Screen.Player,
            navForward = true,
        )
        if (state.detailInfo == null) loadDetailInfo(subject.id)
    }

    /** 用户主动换源。 */
    fun switchSource(rule: AnimeRule, item: SearchItem) {
        autoPlayArmed = true
        attempting = false
        state = state.copy(autoSwitches = 0, failedSources = emptySet(), error = null)
        attemptPlay(rule, item)
    }

    /** 集数对齐：换源时按"第 N 集"找，而不是直接用索引。 */
    private fun matchEpisodeIndex(road: Road, number: Int): Int? {
        road.names.forEachIndexed { index, name ->
            if (parseEpisodeNumber(name) == number) return index
        }
        // 没有任何集名时按顺序推断，但必须确认线路里真有这么多集；
        // 这里**不能**回退到最后集，否则第 5 集会静默播成第 4 集。
        if (road.names.all { it.isNullOrBlank() }) {
            return (number - 1).takeIf { it in road.urls.indices }
        }
        return null
    }

    private fun parseEpisodeNumber(name: String?): Int? =
        name?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

    // ---------- 规则 ----------

    fun importRules(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            showError("内容为空")
            return
        }

        // 支持三种格式：单个对象、对象数组、NDJSON（每行一条，我们导出的 rules.ndjson 就是这种）
        val elements: List<JsonElement> = runCatching {
            when (val e = Json.parseToJsonElement(trimmed)) {
                is JsonArray -> e.toList()
                else -> listOf(e)
            }
        }.getOrElse {
            trimmed.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("{") || it.startsWith("[") }
                .flatMap { line ->
                    runCatching { Json.parseToJsonElement(line) }.getOrNull()
                        ?.let { el -> if (el is JsonArray) el.toList() else listOf(el) }
                        .orEmpty()
                }
                .toList()
        }

        if (elements.isEmpty()) {
            showError("没有解析到规则（不是有效的 JSON / NDJSON）")
            return
        }

        val rules = try {
            if (elements.size == 1 && Css1Converter.looksLikeCss1(elements.first())) {
                importCss1(elements.first())
            } else {
                elements.mapNotNull { el ->
                    runCatching { RuleJson.decodeFromJsonElement(AnimeRule.serializer(), el) }.getOrNull()
                }
            }
        } catch (e: Exception) {
            showError("规则解析失败: ${e.message}")
            return
        }

        if (rules.isEmpty()) {
            showError("解析到 ${elements.size} 条，但没有一条可用")
            return
        }
        state = state.copy(rules = mergeRules(state.rules, rules), error = null)
        appendLog("导入 ${rules.size} 条规则（共 ${state.rules.size} 条）")
        persist()
    }

    /**
     * CSS1 规则包（Kazumi Hub / 番遇的 css1.json）直接粘贴导入。
     *
     * 转换在 core 里做：CSS 选择器翻译成 XPath，播放地址仍交给 WebView 嗅探，
     * 所以不需要移植他们的 `matchVideo` 提取链。
     */
    private fun importCss1(element: JsonElement): List<AnimeRule> {
        val outcome = Css1Converter.convert(element, state.rules.map { it.name }.toSet())
        outcome.skipped.take(5).forEach { appendLog("CSS1 跳过：$it") }
        outcome.notes.take(8).forEach(::appendLog)
        appendLog("CSS1 规则包：${outcome.rules.size} 条可导入，${outcome.skipped.size} 条跳过")
        return outcome.rules
    }

    /** 同名的新规则覆盖旧规则：规则包更新后必须真的生效。 */
    private fun mergeRules(existing: List<AnimeRule>, incoming: List<AnimeRule>): List<AnimeRule> =
        (existing + incoming).associateBy { it.name }.values.toList()

    fun addDemoRule() = importRules(DEMO_RULE)

    /**
     * 从规则源导入。
     *
     * 只拉 `index.json` 里列出的规则（仓库用 deprecated 标记下架的不会进来），
     * 逐条失败不影响整体；全部失败才报错。
     */
    fun importRuleRepo() {
        if (state.busy) return
        state = state.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fetchRepoRules() } }
            state = state.copy(busy = false)
            result.onSuccess { (rules, source) ->
                state = state.copy(rules = mergeRules(state.rules, rules))
                appendLog("规则源 $source：导入 ${rules.size} 条")
                persist()
            }.onFailure { showError("规则源导入失败: ${it.message}") }
        }
    }

    private fun fetchRepoRules(): Pair<List<AnimeRule>, String> {
        var lastError: Throwable? = null
        for (base in RULE_REPO_MIRRORS) {
            val attempt = runCatching {
                val indexText = fetchText(base + "index.json", "")
                val names = Json.parseToJsonElement(indexText).jsonArray
                    .mapNotNull { element ->
                        element.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                    }
                    .filter { it.isNotBlank() }
                    .distinct()

                val rules = ArrayList<AnimeRule>()
                for (name in names) {
                    val text = runCatching { fetchText(base + name + ".json", "") }.getOrNull() ?: continue
                    runCatching { parseRule(text) }.getOrNull()?.let { rules += it }
                }
                if (rules.isEmpty()) error("索引里没有可用规则")
                rules to base.substringAfter("//").substringBefore("/")
            }
            attempt.onSuccess { return it }
            lastError = attempt.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("所有镜像都不可用")
    }

    fun selectRule(rule: AnimeRule) {
        state = state.copy(
            selected = rule,
            screen = Screen.Search,
            hits = emptyList(),
            roads = emptyList(),
            error = null,
        )
    }

    /** 不指定源，搜索全部规则。 */
    fun searchAll() {
        state = state.copy(
            selected = null,
            screen = Screen.Search,
            hits = emptyList(),
            completed = 0,
            total = 0,
            error = null,
        )
    }

    // ---------- 搜索 / 选集 ----------

    /**
     * 并发搜索。
     *
     * - 指定了源就只搜那一个（调试单条规则用），否则搜全部；
     * - 并发度固定 4，避免一次打十几个站点被风控；
     * - 每个源完成就立刻回填 UI，用户不用等最慢的那个；
     * - 不去重，失败/空结果原地展示。
     */
    fun search(keyword: String) {
        if (keyword.isBlank()) return
        val rules = state.selected?.let { listOf(it) } ?: state.rules
        if (rules.isEmpty()) {
            showError("先导入规则")
            return
        }

        state = state.copy(
            keyword = keyword,
            busy = true,
            error = null,
            searchMode = SearchMode.Sources,
            hits = rules.map { SourceHit(rule = it, ruleName = it.name, done = false) },
            completed = 0,
            total = rules.size,
        )
        appendLog("搜索「$keyword」开始：${rules.size} 个源（并发 4）")

        viewModelScope.launch {
            val gate = Semaphore(4)
            rules.forEach { rule ->
                launch {
                    gate.withPermit {
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching { engine.search(rule, keyword) }
                        }
                        val hit = outcome.fold(
                            onSuccess = { result ->
                                SourceHit(
                                    rule = rule,
                                    ruleName = rule.name,
                                    items = result.items,
                                    done = true,
                                )
                            },
                            onFailure = { error ->
                                SourceHit(
                                    rule = rule,
                                    ruleName = rule.name,
                                    error = describe(error),
                                    done = true,
                                )
                            },
                        )
                        state = state.copy(
                            hits = state.hits.map { if (it.ruleName == hit.ruleName) hit else it },
                            completed = state.completed + 1,
                        )
                        // 播放管线：来了一个能用的源就试着起播
                        maybeAutoPlay()
                        if (state.completed >= state.total) {
                            state = state.copy(busy = false)
                            val ok = state.hits.count { it.done && it.error == null && it.items.isNotEmpty() }
                            appendLog("搜索「$keyword」完成：$ok/${state.total} 个源有结果")
                            maybeAutoPlay()
                            // 有结果但都被版本标记筛掉：说清楚，别让用户干等
                            if (autoPlayArmed && state.playPhase == PlayPhase.Searching && candidates().isEmpty()) {
                                autoPlayArmed = false
                                state = state.copy(
                                    screen = Screen.Player,
                                    playPhase = PlayPhase.Failed,
                                    error = "没有标题对得上的源（可能搜到的是其它季/剧场版），可以手动换源",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** 搜索页「直接搜片源」：不自动起播，只把结果列出来。 */
    fun searchSources(keyword: String) {
        rememberKeyword(keyword)
        autoPlayArmed = false
        attempting = false
        triedSources.clear()
        search(keyword)
    }

    fun openChapters(rule: AnimeRule, item: SearchItem) {
        state = state.copy(busy = true, error = null)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { engine.chapters(rule, item.src) }
            }
            state = state.copy(busy = false)
            result.onSuccess { outcome ->
                appendLog("「${item.name}」：${outcome.roads.size} 条线路")
                outcome.diagnostics.forEach(::appendLog)
                state = state.copy(
                    roads = outcome.roads,
                    title = item.name,
                    screen = Screen.Chapters,
                    playSubjectId = state.detail?.id ?: 0,
                    entryRuleName = rule.name,
                    entrySrc = item.src,
                    entryName = item.name,
                )
            }.onFailure { showError(it.message ?: it.toString()) }
        }
    }

    /** 把异常翻译成用户能理解的一句话。 */
    private fun describe(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "域名解析失败"
        is java.net.SocketTimeoutException -> "连接超时"
        is javax.net.ssl.SSLHandshakeException -> "证书/SSL 错误"
        is javax.net.ssl.SSLException -> "SSL 协议错误"
        is java.net.ConnectException -> "连接被拒绝"
        is java.net.SocketException -> "连接被重置"
        is com.dmanime.rules.error.HttpStatusException -> "HTTP ${error.status}"
        is com.dmanime.rules.error.CaptchaRequiredException -> "需要验证码"
        is com.dmanime.rules.error.RuleFormatException -> "规则无效：${error.message?.take(40)}"
        else -> error.message?.take(60) ?: error.javaClass.simpleName
    }

    fun play(road: Road, index: Int) {
        gateCandidate = null
        state = state.copy(episodeGate = null)
        val url = road.urls.getOrNull(index) ?: return
        val episode = road.names.getOrNull(index) ?: "第${index + 1}集"
        state = state.copy(
            screen = Screen.Player,
            title = "${state.title.substringBefore(" · ")} · $episode",
            playPageUrl = url,
            playMedia = null,
            playRoadIndex = state.roads.indexOf(road).coerceAtLeast(0),
            playEpisodeIndex = index,
            playStartMs = resumePositionFor(index),
            episodeNumber = parseEpisodeNumber(episode) ?: (index + 1),
            playPhase = PlayPhase.Starting,
            // 换集就把上一集的弹幕清掉，等新的这条流起来再拉
            danmakuComments = emptyList(),
            danmakuSource = "",
            danmakuError = "",
            danmakuMissing = "",
            danmakuLoading = false,
        )
        danmakuLoadedKey = ""
        appendLog("打开播放页: $url")
    }

    /**
     * 该集的上次播放位置。
     *
     * 只对"最后一次看的那一集"生效（追番记录按番剧存一条），
     * 并且过滤掉太短（<15 秒，多半是误点）和接近片尾（>95%，视为看完）的情况。
     */
    private fun resumePositionFor(episodeIndex: Int): Long {
        val subjectId = state.playSubjectId
        if (subjectId == 0 || episodeIndex < 0) return 0
        val entry = state.watches.firstOrNull { it.subjectId == subjectId } ?: return 0
        if (entry.episodeIndex != episodeIndex) return 0
        if (entry.positionMs < 15_000) return 0
        if (entry.durationMs > 0 && entry.positionMs > entry.durationMs * 0.95) return 0
        return entry.positionMs
    }

    /** 调试入口：跳过规则，直接打开一个播放页，用于单独验证嗅探链路。 */
    fun playDebugUrl(url: String) {
        state = state.copy(
            screen = Screen.Player,
            title = "调试播放",
            playPageUrl = url,
            playMedia = null,
            error = null,
        )
        appendLog("调试播放页: $url")
    }

    /** 调试入口：直接触发一次搜索（可指定源名），用于自动化验证。 */
    fun debugSearch(ruleName: String?, keyword: String) {
        if (keyword.isBlank()) return
        val rule = ruleName?.let { name ->
            state.rules.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
        state = state.copy(selected = rule, screen = Screen.Search, navForward = true)
        search(keyword)
    }

    // ---------- 下载 ----------

    /** 进下载页。 */
    fun openDownloads() {
        refreshDownloads()
        state = state.copy(
            screen = Screen.Downloads,
            navForward = true,
            navStack = state.navStack + state.screen,
            downloadTip = "",
        )
    }

    fun refreshDownloads() {
        if (!DownloadUtil.ready) return
        val imageBase = state.imageBase
        viewModelScope.launch {
            val (list, subjects) = withContext(Dispatchers.IO) {
                val items = runCatching { DownloadUtil.list() }.getOrDefault(emptyList())
                var metas = runCatching { SubjectMetaStore.all(getApplication()) }
                    .getOrDefault(emptyList())
                    .associateBy { it.id }
                // 旧版本的下载没存过元信息：用下载记录里的标题/封面补一份，否则卡片上
                // 只剩「未知番剧」一行字，离线时什么都看不出
                val orphan = items.map { it.meta.subjectId }.distinct().filter { metas[it] == null }
                if (orphan.isNotEmpty()) {
                    orphan.forEach { id ->
                        val m = items.firstOrNull { it.meta.subjectId == id }?.meta ?: return@forEach
                        if (m.subjectTitle.isBlank()) return@forEach
                        SubjectMetaStore.ensure(
                            getApplication(),
                            SubjectMeta(id = id, title = m.subjectTitle, coverUrl = fixedCover(m.cover, imageBase)),
                        )
                    }
                    metas = runCatching { SubjectMetaStore.all(getApplication()) }
                        .getOrDefault(emptyList())
                        .associateBy { it.id }
                }
                // 老下载记录没存本地封面（或者当初用的是直连地址）时补一次，失败就放弃
                items.map { it.meta.subjectId }.distinct().forEach { id ->
                    val m = metas[id] ?: return@forEach
                    if (m.coverUrl.isBlank()) return@forEach
                    if (SubjectMetaStore.localCover(getApplication(), id) != null) return@forEach
                    if (!coverRetry.add(id)) return@forEach
                    SubjectMetaStore.fetchCover(getApplication(), id, fixedCover(m.coverUrl, imageBase))
                }
                items to metas
            }
            state = state.copy(downloads = list, downloadSubjects = subjects)
        }
    }

    /** lain.bgm.tv 在多数网络下直连不通，统一换成设置里的图片前缀。 */
    private fun fixedCover(url: String, base: String): String =
        if (base.isBlank() || url.isBlank()) url
        else url.replace("https://lain.bgm.tv", base).replace("http://lain.bgm.tv", base)

    /** 本地封面的绝对路径（给下载页用），没存过返回 null。 */
    fun localCoverPath(subjectId: Int): String? =
        SubjectMetaStore.localCover(getApplication(), subjectId)?.absolutePath

    /** 当前正在看的这一集的下载记录（没有则 null），用于播放页按钮状态。 */
    fun downloadOfCurrent(): DownloadItem? {
        val subjectId = state.playSubjectId
        if (subjectId == 0) return null
        return state.downloads.firstOrNull {
            it.meta.subjectId == subjectId && it.meta.episodeIndex == state.playEpisodeIndex
        }
    }

    /**
     * 下载正在看的这一集。
     *
     * 必须等播放地址已经解析出来：下载要用真实的 m3u8/mp4 地址和它自己的请求头，
     * 拿播放页 URL 去下只会下到一个 HTML。
     */
    fun downloadCurrentEpisode() {
        val media = state.playMedia
        if (media == null) {
            state = state.copy(downloadTip = "还没解析出播放地址，等画面出来再点下载")
            return
        }
        val subjectId = state.playSubjectId
        if (subjectId == 0) {
            state = state.copy(downloadTip = "调试播放不支持下载（缺少番剧信息）")
            return
        }
        val epIndex = state.playEpisodeIndex
        if (epIndex < 0) {
            state = state.copy(downloadTip = "还没选中具体集数")
            return
        }
        val key = DownloadUtil.cacheKey(subjectId, epIndex)
        if (DownloadUtil.find(key) != null) {
            state = state.copy(downloadTip = "这一集已经在下载列表里了")
            return
        }

        val subject = state.detail
        val road = state.roads.getOrNull(state.playRoadIndex)
        val epName = road?.names?.getOrNull(epIndex) ?: "第${epIndex + 1}集"
        val subjectTitle = subject?.displayTitle ?: state.title.substringBefore(" · ")
        // 封面存成"这台网络能打开的地址"：直连 lain.bgm.tv 在多数网络下超时，
        // 存原始 URL 会导致下载页空白 + 离线封面永远下不下来
        val coverUrl = subject?.coverUrl.orEmpty().let { url ->
            if (state.imageBase.isBlank()) url
            else url.replace("https://lain.bgm.tv", state.imageBase)
                .replace("http://lain.bgm.tv", state.imageBase)
        }
        val meta = DownloadMeta(
            subjectId = subjectId,
            episodeIndex = epIndex,
            episodeName = epName,
            subjectTitle = subjectTitle,
            cover = coverUrl,
            url = media.url,
            headers = media.headers,
        )

        runCatching {
            androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                getApplication(),
                AppDownloadService::class.java,
                DownloadUtil.buildRequest(meta),
                /* foreground = */ true,
            )
        }.onSuccess {
            state = state.copy(downloadTip = "")
            appendLog("加入下载：$subjectTitle $epName")
            refreshDownloads()
            // 番剧标题/简介/封面一起存到本地：离线时下载页还要显示图和简介
            val info = state.detailInfo
            val subjectMeta = SubjectMeta(
                id = subjectId,
                title = subjectTitle,
                originalTitle = subject?.name.orEmpty(),
                summary = info?.summary?.ifBlank { null } ?: subject?.summary.orEmpty(),
                coverUrl = coverUrl,
                totalEpisodes = info?.episodeCount ?: 0,
                airDate = info?.airDate ?: subject?.airDate.orEmpty(),
                rating = (info?.rating?.score ?: 0.0).takeIf { it > 0 }
                    ?: (subject?.rating?.score ?: 0.0),
                tags = info?.tags?.map { it.name }?.take(8).orEmpty(),
            )
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { SubjectMetaStore.ensure(getApplication(), subjectMeta) }
                }
                refreshDownloads()
            }
        }.onFailure {
            showError("下载没起来：${describe(it)}")
        }
    }

    fun pauseDownload(id: String) {
        runCatching {
            androidx.media3.exoplayer.offline.DownloadService.sendSetStopReason(
                getApplication(),
                AppDownloadService::class.java,
                id,
                PAUSE_STOP_REASON,
                false,
            )
        }
        refreshDownloads()
    }

    fun resumeDownload(id: String) {
        runCatching {
            androidx.media3.exoplayer.offline.DownloadService.sendSetStopReason(
                getApplication(),
                AppDownloadService::class.java,
                id,
                androidx.media3.exoplayer.offline.Download.STOP_REASON_NONE,
                false,
            )
        }
        refreshDownloads()
    }

    fun removeDownload(id: String) {
        runCatching {
            androidx.media3.exoplayer.offline.DownloadService.sendRemoveDownload(
                getApplication(),
                AppDownloadService::class.java,
                id,
                false,
            )
        }
        refreshDownloads()
    }

    fun removeAllDownloads() {
        val subjectIds = state.downloads.map { it.meta.subjectId }.toSet()
        runCatching {
            androidx.media3.exoplayer.offline.DownloadService.sendRemoveAllDownloads(
                getApplication(),
                AppDownloadService::class.java,
                false,
            )
        }
        state = state.copy(downloads = emptyList(), downloadSubjects = emptyMap())
        // 元信息和封面跟着删，否则删光了还占着几百 KB
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                subjectIds.forEach { runCatching { SubjectMetaStore.remove(getApplication(), it) } }
            }
        }
    }

    /** App 被杀过之后，media3 不会自己把没下完的重新排上，需要手动叫一下。 */
    fun resumeAllDownloads() {
        val pending = state.downloads.filter { !it.done && !it.failed && !it.removing }
        pending.forEach { resumeDownload(it.id) }
        appendLog("继续下载 ${pending.size} 个任务")
    }

    /** 播放已下载的一集：走缓存，不联网。 */
    fun playDownload(item: DownloadItem) {
        state = state.copy(
            screen = Screen.Player,
            title = "${item.meta.subjectTitle} · ${item.meta.episodeName}".trim(),
            playPageUrl = "",
            playMedia = PlayMedia(
                url = item.meta.url,
                headers = item.meta.headers,
                note = "本地缓存",
            ),
            playStartMs = 0,
            playPhase = PlayPhase.Playing,
            playSubjectId = item.meta.subjectId,
            playEpisodeIndex = item.meta.episodeIndex,
            episodeNumber = item.meta.episodeIndex + 1,
            roads = emptyList(),
            playerBackTo = Screen.Downloads,
            downloadTip = "",
        )
        maybeLoadDanmaku()
    }

    private fun failureText(reason: Int): String = when (reason) {
        androidx.media3.exoplayer.offline.Download.FAILURE_REASON_NONE -> "未知"
        else -> "错误码 $reason"
    }

    // ---------- 弹幕 ----------

    /** 播放就绪后叫一次：有片名和集号就去 B 站找这一集的弹幕。 */
    private fun maybeLoadDanmaku() {
        val subjectId = state.playSubjectId
        val episode = state.playEpisodeIndex + 1
        if (subjectId == 0 || episode <= 0) return
        loadDanmakuFor(subjectId, episode)
    }

    private fun loadDanmakuFor(subjectId: Int, episode: Int) {
        val key = "$subjectId-$episode"
        if (danmakuLoadedKey == key) return
        danmakuLoadedKey = key
        val titles = danmakuTitles()
        if (titles.isEmpty()) {
            state = state.copy(danmakuError = "不知道这一集叫什么，没法匹配弹幕")
            return
        }
        val total = state.detailInfo?.totalEpisodes?.takeIf { it > 0 }
            ?: state.detailInfo?.episodeCount
            ?: 0
        val origins = state.danmakuOrigins.ifEmpty { setOf(DanmakuOrigin.Catalog) }
        state = state.copy(
            danmakuComments = emptyList(),
            danmakuSource = "",
            danmakuError = "",
            danmakuMissing = "",
            danmakuLoading = true,
        )
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                danmakuRepo.load(subjectId, episode, titles, total, origins)
            }
            result.onSuccess { bundle ->
                val originLabel = bundle.origins
                    .sortedBy { DanmakuOrigin.entries.indexOf(it) }
                    .joinToString(" + ") { it.shortLabel }
                val missing = bundle.failures.entries
                    .sortedBy { DanmakuOrigin.entries.indexOf(it.key) }
                    .joinToString("；") { "${it.key.shortLabel}：${it.value}" }
                state = state.copy(
                    danmakuLoading = false,
                    danmakuComments = bundle.comments,
                    danmakuSource = "$originLabel · ${bundle.seasonTitle} ${bundle.episodeTitle}".trim(),
                    danmakuMissing = missing,
                )
                appendLog("弹幕 ${bundle.comments.size} 条（$originLabel · ${bundle.seasonTitle}）")
                if (missing.isNotBlank()) appendLog("弹幕来源缺：$missing")
            }.onFailure { error ->
                // 失败不清 key：留着让人能在设置里手动重试
                danmakuLoadedKey = ""
                state = state.copy(
                    danmakuLoading = false,
                    danmakuComments = emptyList(),
                    danmakuError = error.message ?: "取弹幕失败",
                    danmakuMissing = "",
                )
                appendLog("弹幕不可用：${error.message}")
            }
        }
    }

    /** 拿去搜 B 站的片名候选：中文名、原名、当前标题，按可能性排序。 */
    private fun danmakuTitles(): List<String> {
        val detail = state.detail
        val list = mutableListOf<String>()
        detail?.nameCn?.takeIf { it.isNotBlank() }?.let { list += it }
        detail?.name?.takeIf { it.isNotBlank() }?.let { list += it }
        state.title.substringBefore(" · ").takeIf { it.isNotBlank() }?.let { list += it }
        return list.distinct()
    }

    fun toggleDanmaku() {
        val next = !state.danmakuOn
        uiPrefs.edit().putBoolean("danmaku_on", next).apply()
        state = state.copy(danmakuOn = next)
        if (next && state.danmakuComments.isEmpty() && state.danmakuError.isBlank()) maybeLoadDanmaku()
    }

    /**
     * 勾上/取消一个弹幕来源。
     *
     * 至少留一个——全取消就没东西可显示。改了来源必须重取：不同来源的标题、
     * 集号都对不上，之前的缓存也不能直接复用。
     */
    fun toggleDanmakuOrigin(origin: DanmakuOrigin) {
        if (origin !in state.danmakuPickable) return
        val next = if (origin in state.danmakuOrigins) {
            state.danmakuOrigins - origin
        } else {
            state.danmakuOrigins + origin
        }
        if (next.isEmpty()) return
        uiPrefs.edit().putString("danmaku_origins", next.joinToString(",") { it.name }).apply()
        state = state.copy(danmakuOrigins = next)
        danmakuLoadedKey = ""
        if (!state.danmakuOn) return
        val subjectId = state.playSubjectId
        val episode = state.playEpisodeIndex + 1
        if (subjectId != 0 && episode > 0) loadDanmakuFor(subjectId, episode)
    }

    fun setDanmakuAlpha(value: Float) {
        val clamped = value.coerceIn(0.1f, 1f)
        uiPrefs.edit().putFloat("danmaku_alpha", clamped).apply()
        state = state.copy(danmakuAlpha = clamped)
    }

    fun setDanmakuScale(value: Float) {
        val clamped = value.coerceIn(0.6f, 2f)
        uiPrefs.edit().putFloat("danmaku_scale", clamped).apply()
        state = state.copy(danmakuScale = clamped)
    }

    /** 弹幕区高度占比：太小挤成一条，铺满整个画面又会盖住字幕。 */
    fun setDanmakuArea(value: Float) {
        val clamped = value.coerceIn(0.3f, 1f)
        uiPrefs.edit().putFloat("danmaku_area", clamped).apply()
        state = state.copy(danmakuArea = clamped)
    }

    fun setDanmakuSpeed(value: Float) {
        val clamped = value.coerceIn(0.6f, 1.6f)
        uiPrefs.edit().putFloat("danmaku_speed", clamped).apply()
        state = state.copy(danmakuSpeed = clamped)
    }

    /** 手动对齐：片头长度各家不一样，自动排不齐时让用户自己挪。 */
    fun shiftDanmakuOffset(deltaMs: Long) {
        val clamped = (state.danmakuOffsetMs + deltaMs).coerceIn(-60_000L, 60_000L)
        uiPrefs.edit().putLong("danmaku_offset", clamped).apply()
        state = state.copy(danmakuOffsetMs = clamped)
    }

    fun resetDanmakuOffset() {
        uiPrefs.edit().putLong("danmaku_offset", 0L).apply()
        state = state.copy(danmakuOffsetMs = 0L)
    }

    /** 重新配一次：换番/换源后集号对不上时用。 */
    fun reloadDanmaku() {
        danmakuLoadedKey = ""
        val subjectId = state.playSubjectId
        val episode = state.playEpisodeIndex + 1
        if (subjectId == 0 || episode <= 0) return
        if (state.detailInfo?.id != subjectId) loadDetailInfo(subjectId)
        loadDanmakuFor(subjectId, episode)
    }

    // ---------- 嗅探 ----------

    fun onSniffCandidate(url: String) {
        val current = state
        if (current.playMedia != null) return

        val candidate = SniffCandidate(url, MediaUrls.classify(url))
        appendLog("嗅探到 ${candidate.kind}: $url")
        if (candidate.kind == MediaKind.UNKNOWN) return

        val referer = current.playPageUrl
        viewModelScope.launch {
            val media = withContext(Dispatchers.IO) {
                runCatching {
                    SniffResolver(fetch = { fetchText(it, referer) })
                        .resolve(candidate, referer = referer, userAgent = DEFAULT_UA)
                }.getOrNull()
            }
            if (media != null && state.playMedia == null) {
                state = state.copy(
                    playMedia = PlayMedia(
                        url = media.url,
                        headers = media.headers,
                        note = if (media.encrypted) "AES-128 加密流，由播放器解密" else "",
                    ),
                    playPhase = PlayPhase.Playing,
                )
                appendLog("开始播放: ${media.url}")
                maybeLoadDanmaku()
            }
        }
    }

    fun appendLog(message: String) {
        Log.d("dmanime", message)
        state = state.copy(logs = (state.logs + message).takeLast(100))
    }

    fun showError(message: String) {
        Log.e("dmanime", message)
        state = state.copy(error = message)
        appendLog("错误: $message")
    }

    fun back() {
        state = state.copy(navForward = false)
        state = when (state.screen) {
            // 返回回到"进入播放前的那一屏"：详情/首页/搜索，而不是选集
            Screen.Player -> state.copy(
                screen = state.playerBackTo,
                playPageUrl = "",
                playMedia = null,
                playPhase = PlayPhase.Idle,
                playerFullscreen = false,
                // 回搜索页时切回索引模式，别停在"原始搜源"那个调试界面
                searchMode = if (state.playerBackTo == Screen.Search) SearchMode.Index else state.searchMode,
            )

            Screen.Downloads -> state.copy(
                screen = state.navStack.lastOrNull() ?: Screen.Mine,
                navStack = state.navStack.dropLast(1),
                downloadTip = "",
            )

            // 源体检 / 季度番剧 / 人机验证：统一弹栈回到进来时那一屏。
            // 以前这三个没有分支，点「返回」什么都不会发生。
            Screen.Health, Screen.Season, Screen.Verify -> state.copy(
                screen = state.navStack.lastOrNull() ?: Screen.Mine,
                navStack = state.navStack.dropLast(1),
                verifyUrl = "",
                verifyRuleName = "",
                verifyDone = false,
                downloadTip = "",
            )

            Screen.Chapters -> state.copy(
                screen = if (state.detail != null) Screen.Detail else Screen.Search,
                roads = emptyList(),
            )

            // 弹回"打开详情前那一屏"：搜索/首页/季度表/播放页都能正确回去。
            // 播放↔详情互相打开时，靠每次打开都压栈、返回都弹栈来断环。
            Screen.Detail -> {
                val target = state.detailStack.lastOrNull() ?: state.detailOrigin
                state.copy(
                    screen = target,
                    detailStack = state.detailStack.dropLast(1),
                    // 回播放页要留着详情数据（播放页顶部还在用），回列表页才清
                    detail = if (target == Screen.Player) state.detail else null,
                    hits = emptyList(),
                )
            }

            else -> state
        }
    }

    // ---------- 内部 ----------

    private fun fetchText(url: String, referer: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", DEFAULT_UA)
            if (referer.isNotBlank()) setRequestProperty("Referer", referer)
        }
        return connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }

    private fun persist() {
        runCatching { ruleFile.writeText(state.rules.joinToString("\n") { encodeRule(it) }) }
    }

    private fun loadRules() {
        if (!ruleFile.exists()) return
        val text = ruleFile.readText()
        if (text.isBlank()) return

        // 同时支持两种落盘格式：整块 JSON（单对象/数组）与 NDJSON（一行一条）。
        // 手写/调试时很容易把美化过的 JSON 直接塞进来，不应该因此丢规则。
        val wholeFile: List<AnimeRule>? = runCatching {
            when (val element = Json.parseToJsonElement(text)) {
                is JsonArray -> element.map { RuleJson.decodeFromJsonElement(AnimeRule.serializer(), it) }
                is JsonObject -> listOf(RuleJson.decodeFromJsonElement(AnimeRule.serializer(), element))
                else -> emptyList<AnimeRule>()
            }
        }.getOrNull()

        val rules = wholeFile ?: text.split('\n')
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .mapNotNull { line -> runCatching { parseRule(line) }.getOrNull() }

        state = state.copy(rules = rules)
    }
}
