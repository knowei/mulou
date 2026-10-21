package com.dmanime.app

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.dmanime.app.download.DownloadItem
import com.dmanime.app.download.humanSize
import com.dmanime.catalog.CatalogSubject
import com.dmanime.rules.model.AnimeRule
import com.dmanime.rules.model.SearchItem
import com.dmanime.catalog.today
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDate

// ------------------------------------------------------------------ 主题（木漏 Komo 配色）

/**
 * 取自 design/README.md 的调色板：
 * 莫兰迪青绿为主，暖金晨曦做强调，浅色底用晨光米白，深色底用深邃黛绿。
 */
private val KomoLightColors = lightColorScheme(
    primary = Color(0xFF6E8B7A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0EAE2),
    onPrimaryContainer = Color(0xFF1E2A24),
    secondary = Color(0xFFD9A544),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFCEBC7),
    onSecondaryContainer = Color(0xFF3A2A00),
    background = Color(0xFFFFFDF9),
    onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFFFFDF9),
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFE0EAE2),
    onSurfaceVariant = Color(0xFF47524B),
    outline = Color(0xFF9FAFA4),
    error = Color(0xFFB3261E),
)

private val KomoDarkColors = darkColorScheme(
    primary = Color(0xFFA8C4B2),
    onPrimary = Color(0xFF12301F),
    primaryContainer = Color(0xFF2A3D33),
    onPrimaryContainer = Color(0xFFD6E7DC),
    secondary = Color(0xFFFCD074),
    onSecondary = Color(0xFF3A2A00),
    secondaryContainer = Color(0xFF4A3A12),
    onSecondaryContainer = Color(0xFFFCEBC7),
    background = Color(0xFF0F1613),
    onBackground = Color(0xFFE3E9E4),
    surface = Color(0xFF151E1A),
    onSurface = Color(0xFFE3E9E4),
    surfaceVariant = Color(0xFF26332C),
    onSurfaceVariant = Color(0xFFB6C2BA),
    outline = Color(0xFF5C6B62),
    error = Color(0xFFF2B8B5),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 调试入口只存在于 debug 包：正式包里这些 extra 一律不认，
        // 免得有人用 `am start --es play_url ...` 把应用当播放器壳子使。
        val debugEnabled = BuildConfig.DEBUG
        val debugUrl = if (debugEnabled) intent?.getStringExtra("play_url") else null
        val debugSearch = if (debugEnabled) intent?.getStringExtra("search") else null
        val debugRule = if (debugEnabled) intent?.getStringExtra("rule") else null
        val debugIndexFile = if (debugEnabled) intent?.getStringExtra("index_json_file") else null
        val debugVerifyRule = if (debugEnabled) intent?.getStringExtra("verify_rule") else null
        val debugVerifyKeyword = if (debugEnabled) intent?.getStringExtra("verify_keyword") else null
        val debugRulesFile = if (debugEnabled) intent?.getStringExtra("rules_json_file") else null
        val alertId = intent?.getLongExtra("alert_id", 0L) ?: 0L
        setContent {
            val vm: AppViewModel = viewModel()
            val darkTheme = when (vm.state.themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            // 状态栏 / 导航栏图标颜色跟随主题
            SideEffect {
                val controller = androidx.core.view.WindowCompat.getInsetsController(
                    window, window.decorView
                )
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
            MaterialTheme(
                colorScheme = if (darkTheme) KomoDarkColors else KomoLightColors,
            ) {
                // 通知点进来：直接播那一集
                LaunchedEffect(alertId) {
                    if (alertId != 0L) vm.openAlert(alertId)
                }
                // Android 13+ 通知权限
                LaunchedEffect(Unit) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        val ctx = this@MainActivity
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                            ctx, android.Manifest.permission.POST_NOTIFICATIONS
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            androidx.core.app.ActivityCompat.requestPermissions(
                                ctx, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
                            )
                        }
                    }
                }
                LaunchedEffect(debugUrl) {
                    if (!debugUrl.isNullOrBlank()) vm.playDebugUrl(debugUrl)
                }
                LaunchedEffect(debugSearch) {
                    if (!debugSearch.isNullOrBlank()) vm.debugSearch(debugRule, debugSearch)
                }
                LaunchedEffect(debugIndexFile) {
                    if (!debugIndexFile.isNullOrBlank()) {
                        runCatching { java.io.File(debugIndexFile).readText() }
                            .onSuccess { vm.importIndexJson(it) }
                            .onFailure { vm.showError("读取索引文件失败: ${it.message}") }
                    }
                }
                // 调试入口：直接打开某条规则的人机验证页（验证 Cloudflare 挑战流程）
                LaunchedEffect(debugVerifyRule) {
                    if (!debugVerifyRule.isNullOrBlank()) {
                        val rule = vm.state.rules.firstOrNull { it.name == debugVerifyRule }
                        if (rule != null) {
                            vm.updateKeyword(if (debugVerifyKeyword.isNullOrBlank()) "葬送的芙莉莲" else debugVerifyKeyword)
                            vm.startVerify(rule)
                        } else {
                            vm.showError("没有这条规则：$debugVerifyRule")
                        }
                    }
                }
                // 调试入口：从 filesDir 下的文件名导入规则（可为 CSS1 规则包），
                // 省去在模拟器里粘贴几十 KB 剪贴板。
                LaunchedEffect(debugRulesFile) {
                    if (!debugRulesFile.isNullOrBlank()) {
                        runCatching { java.io.File(filesDir, debugRulesFile).readText() }
                            .onSuccess { vm.importRules(it) }
                            .onFailure { vm.showError("读取规则文件失败: ${it.message}") }
                    }
                }
                App(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppViewModel) {
    val state = vm.state
    val isRoot = state.screen == Screen.Home || state.screen == Screen.Search || state.screen == Screen.Mine
    BackHandler(enabled = !isRoot) { vm.back() }

    Scaffold(
        topBar = {
            if (!isRoot && !state.playerFullscreen) {
                TopAppBar(
                    title = {
                        Text(
                            text = when (state.screen) {
                                Screen.Detail -> state.detail?.displayTitle ?: "详情"
                                Screen.Chapters -> state.title.ifEmpty { "选集" }
                                Screen.Player -> state.title.ifEmpty { "播放" }
                                Screen.Verify -> "人机验证 · ${state.verifyRuleName}"
                                Screen.Health -> "源体检"
                                Screen.Season -> "季度番剧"
                                Screen.Downloads -> "下载"
                                else -> ""
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        TextButton(
                            onClick = {
                                if (state.screen == Screen.Verify) vm.cancelVerify() else vm.back()
                            }
                        ) { Text("返回") }
                    },
                    actions = {
                        if (state.screen == Screen.Verify) {
                            TextButton(onClick = vm::finishVerify) { Text("完成") }
                        }
                        if (state.screen == Screen.Player && state.playMedia != null) {
                            val dl = state.downloads.firstOrNull {
                                it.meta.subjectId == state.playSubjectId &&
                                    it.meta.episodeIndex == state.playEpisodeIndex
                            }
                            TextButton(
                                onClick = {
                                    if (dl == null) vm.downloadCurrentEpisode() else vm.openDownloads()
                                },
                            ) {
                                Text(
                                    when {
                                        dl == null -> "下载"
                                        dl.done -> "已下载"
                                        else -> dl.progressText
                                    }
                                )
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (isRoot) {
                NavigationBar {
                    NavigationBarItem(
                        selected = state.screen == Screen.Home,
                        onClick = vm::goHome,
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("追番") },
                    )
                    NavigationBarItem(
                        selected = state.screen == Screen.Search,
                        onClick = vm::goSearch,
                        icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        label = { Text("搜索") },
                    )
                    NavigationBarItem(
                        selected = state.screen == Screen.Mine,
                        onClick = vm::goMine,
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("我的") },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            // 页面转场：前进时新页从右滑入，返回时从左滑入（带淡入，避免生硬）
            val trans = remember { androidx.compose.animation.core.Animatable(1f) }
            var lastScreen by remember { mutableStateOf(state.screen) }
            LaunchedEffect(state.screen) {
                if (state.screen != lastScreen) {
                    lastScreen = state.screen
                    trans.snapTo(0f)
                    trans.animateTo(1f, androidx.compose.animation.core.tween(220))
                }
            }
            val slideDir = if (state.navForward) 1f else -1f
            // 有下载在跑时每秒刷一次：不然下载页的进度条和播放页顶栏的"下载"按钮都不会动
            val hasActiveDownload = state.downloads.any { !it.done && !it.removing }
            LaunchedEffect(hasActiveDownload) {
                while (hasActiveDownload) {
                    kotlinx.coroutines.delay(1000)
                    vm.refreshDownloads()
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = (1f - trans.value) * slideDir * 110f
                        alpha = 0.3f + 0.7f * trans.value
                    }
            ) {
            when (state.screen) {
                Screen.Home -> HomeScreen(vm, state)
                Screen.Search -> SearchScreen(vm, state)
                Screen.Mine -> MineScreen(vm, state)
                Screen.Detail -> DetailScreen(vm, state)
                Screen.Chapters -> ChaptersScreen(vm, state)
                Screen.Player -> PlayerScreen(vm, state)
                Screen.Verify -> VerifyScreen(vm, state)
                Screen.Health -> HealthScreen(vm, state)
                Screen.Season -> SeasonScreen(vm, state)
                Screen.Downloads -> DownloadsScreen(vm, state)
            }
            }
        }
    }
}

// ------------------------------------------------------------------ 首页

@Composable
private fun HomeScreen(vm: AppViewModel, state: UiState) {
    val days = state.home.days
    val today = days.today(remember { LocalDate.now().dayOfWeek.value })
    val season = days.flatMap { it.items }
        .distinctBy { it.id }
        .sortedByDescending { it.rating.score }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "追番",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )
        }

        if (days.isEmpty()) {
            item {
                when {
                    state.home.loading -> LinearProgressIndicator(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )

                    else -> Column(Modifier.padding(16.dp)) {
                        Text(
                            state.home.error?.let { "索引加载失败：$it" } ?: "还没有番剧索引数据",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { vm.loadHome(true) }) { Text("重试") }
                    }
                }
            }
        }

        state.home.error?.takeIf { days.isNotEmpty() }?.let { message ->
            item {
                Text(
                    "索引更新失败（显示的是缓存）：$message",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        if (state.watches.isNotEmpty()) {
            item { SectionHeader("继续观看", "${state.watches.size} 部") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.watches, key = { it.subjectId }) { entry ->
                        ContinueCard(entry, state.imageBase) { vm.resumeWatch(entry) }
                    }
                }
            }
        }

        item { SectionHeader("今日更新", today?.weekday?.cn.orEmpty()) }
        item {
            val items = today?.items.orEmpty()
            if (items.isEmpty()) {
                Text(
                    "今天没有更新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items, key = { it.id }) { subject ->
                        SubjectCard(subject, state.imageBase) { vm.openSubject(subject) }
                    }
                }
            }
        }

        item {
            // 这里只是"正在播出"的一小截，真正的季度全量表在 Season 页
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.openSeason() }
                    .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("本季新番", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (season.isEmpty()) "" else "${season.size} 部在播",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "季度表 ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(season, key = { it.id }) { subject ->
                    SubjectCard(subject, state.imageBase) { vm.openSubject(subject) }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ContinueCard(entry: WatchEntry, imageBase: String, onClick: () -> Unit) {
    Column(
        Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val cover = entry.cover.withImageBase(imageBase)
            if (cover.isNotEmpty()) {
                AsyncImage(
                    model = cover,
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            entry.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            entry.episodeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (entry.progress > 0f) {
            Spacer(Modifier.height(2.dp))
            LinearProgressIndicator(
                progress = { entry.progress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
    }
}

@Composable
private fun SubjectCard(subject: CatalogSubject, imageBase: String, onClick: () -> Unit) {
    Column(Modifier.width(104.dp).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val cover = subject.coverUrlSmall.withImageBase(imageBase)
            if (cover.isNotEmpty()) {
                AsyncImage(
                    model = cover,
                    contentDescription = subject.displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            subject.displayTitle,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subject.rating.score > 0) {
            Text(
                "%.1f".format(subject.rating.score),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ 详情

@Composable
private fun DetailScreen(vm: AppViewModel, state: UiState) {
    val subject = state.detail ?: return
    val info = state.detailInfo
    var expanded by remember(subject.id) { mutableStateOf(false) }
    val watched = state.watches.any { it.subjectId == subject.id }
    val fromPlayer = state.detailOrigin == Screen.Player
    val summary = info?.summary?.takeIf { it.isNotBlank() } ?: subject.summary

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(16.dp)) {
                Box(
                    Modifier
                        .width(110.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val cover = subject.coverUrl.withImageBase(state.imageBase)
                    if (cover.isNotEmpty()) {
                        AsyncImage(
                            model = cover,
                            contentDescription = subject.displayTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(subject.displayTitle, style = MaterialTheme.typography.titleLarge)

                    val originalName = info?.name?.takeIf {
                        it.isNotBlank() && it != subject.displayTitle
                    }
                    if (originalName != null) {
                        Text(
                            originalName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    val score = info?.rating?.score?.takeIf { it > 0 } ?: subject.rating.score
                    if (score > 0) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "%.1f".format(score),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                " 分",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            val total = info?.rating?.total ?: 0
                            if (total > 0) {
                                Text(
                                    "  ·  $total 人评分",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    val meta = buildList {
                        if (info?.episodeLabel?.isNotEmpty() == true) add(info.episodeLabel)
                        if (!info?.platform.isNullOrEmpty()) add(info.platform)
                        val date = info?.airDate?.takeIf { it.isNotEmpty() } ?: subject.airDate
                        if (date.isNotEmpty()) add("放送 $date")
                    }
                    meta.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 下一集什么时候播（数据来自 AniList，匹配不上就不显示）
                    state.airing?.let { airing ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "下一集 第 ${airing.episode} 集 · ${formatAiring(airing.airingAt)} · ${humanizeAiring(airing.timeUntil)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 从播放页进的详情只当"资料页"：不再给播放入口，
                // 否则 播放 → 详情 → 播放 会来回打转，用户退不出去。
                if (!fromPlayer) {
                    Button(
                        onClick = vm::playCurrentSubject,
                        enabled = state.rules.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("播放") }
                }

                OutlinedButton(
                    onClick = vm::toggleWatch,
                    modifier = Modifier.weight(1f),
                ) { Text(if (watched) "已追番" else "追番") }
            }
        }

        // 角色 / 声优
        if (state.characters.isNotEmpty()) {
            item {
                Text(
                    "角色 / 声优",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
                )
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.characters.take(24), key = { it.id }) { character ->
                        Column(Modifier.width(84.dp)) {
                            Box(
                                Modifier
                                    .width(84.dp)
                                    // 角色立绘是全身像：用 2:3 + Fit，宁可有留白也不裁掉头
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(8.dp))
                                    // 立绘本身是白底 JPEG，容器也用白色，和图片融为一体
                                    .background(Color.White),
                                contentAlignment = Alignment.Center,
                            ) {
                                val url = character.imageUrl.withImageBase(state.imageBase)
                                if (url.isNotEmpty()) {
                                    AsyncImage(
                                        model = url,
                                        contentDescription = character.name,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                character.name,
                                style = MaterialTheme.typography.labelSmall,
                                // 固定两行：名字长短不一会让整行高度跳动，下面的选集跟着抖
                                minLines = 2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val cv = character.actorNames
                            Text(
                                if (cv.isBlank()) " " else "CV $cv",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                minLines = 1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // 选集入口：点开弹出集数面板（后台先找源，找到直接铺集数）
        if (!fromPlayer) item {
            val total = info?.totalEpisodes ?: 0
            val aired = info?.eps ?: 0
            val summaryText = when {
                total > 0 && aired in 1 until total -> "更新至 $aired 集 / 共 $total 集"
                total > 0 -> "共 $total 集"
                aired > 0 -> "已更新 $aired 集"
                else -> ""
            }
            OutlinedButton(
                onClick = vm::openEpisodeSheet,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text(if (summaryText.isEmpty()) "选集" else "选集 · $summaryText")
            }
        }

        if (!info?.tags.isNullOrEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(info.tags.take(12), key = { it.name }) { tag ->
                        AssistChip(onClick = {}, label = { Text(tag.name) })
                    }
                }
            }
        }

        if (summary.isNotBlank()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (summary.length > 90) {
                        TextButton(
                            onClick = { expanded = !expanded },
                            contentPadding = PaddingValues(0.dp),
                        ) { Text(if (expanded) "收起" else "展开简介") }
                    }
                }
            }
        }

        if (state.detailLoading) {
            item {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        if (state.rules.isEmpty()) {
            item {
                Text(
                    "还没有规则，去「我的」导入规则源后才能找片源。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        if (state.busy) {
            item {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    // 选集弹层：点某一集直接进播放页
    if (state.episodeSheet) {
        EpisodeSheet(vm, state)
    }
}

/** 选集弹层：点某一集直接进播放页播这集。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeSheet(vm: AppViewModel, state: UiState) {
    ModalBottomSheet(onDismissRequest = vm::closeEpisodeSheet) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("选集", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                val count = state.roads.getOrNull(state.playRoadIndex)?.urls?.size ?: 0
                Text(
                    when {
                        state.episodeLoading -> "正在找片源…"
                        count > 0 -> "共 $count 集"
                        else -> "暂无集数"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val road = state.roads.getOrNull(state.playRoadIndex) ?: state.roads.firstOrNull()
            if (state.episodeLoading || road == null || road.urls.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "正在准备集数…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(road.urls.indices.toList().chunked(4)) { chunk ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            chunk.forEach { index ->
                                val label = road.names.getOrNull(index) ?: "${index + 1}"
                                AssistChip(
                                    onClick = { vm.pickEpisode(road, index) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 搜索

@Composable
private fun SearchScreen(vm: AppViewModel, state: UiState) {    var keyword by remember { mutableStateOf(state.keyword) }
    val all = state.home.days.flatMap { it.items }.distinctBy { it.id }
    val matches = remember(keyword, all) {
        if (keyword.isBlank()) {
            emptyList()
        } else {
            all.filter { it.displayTitle.contains(keyword, ignoreCase = true) || it.name.contains(keyword, ignoreCase = true) }
                .sortedByDescending { it.rating.score }
                .take(30)
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = keyword,
            onValueChange = {
                keyword = it
                vm.updateKeyword(it)
            },
            placeholder = { Text("搜索番剧名字", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (keyword.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = "清空",
                        modifier = Modifier
                            .size(20.dp)
                            .clickable {
                                keyword = ""
                                vm.updateKeyword("")
                            },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (state.searchMode == SearchMode.Index) {
            if (keyword.isBlank()) {
                Column {
                    Text(
                        "输入番名，先查本地索引；点结果直接播放。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    if (state.searchHistory.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "最近搜索",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "清空",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { vm.clearSearchHistory() },
                            )
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(state.searchHistory, key = { it }) { hist ->
                                AssistChip(
                                    onClick = {
                                        keyword = hist
                                        vm.updateKeyword(hist)
                                    },
                                    label = { Text(hist) },
                                )
                            }
                        }
                    }
                }
            } else if (matches.isEmpty() && !state.onlineSearching && state.onlineResults.isEmpty()) {
                Text(
                    "本地索引没有「$keyword」，点右上角「搜片源」去各站找。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            LazyColumn(Modifier.weight(1f)) {
                if (matches.isNotEmpty()) {
                    item(key = "local-header") {
                        Text(
                            "索引 ${matches.size} 部",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
                        )
                    }
                }
                items(matches, key = { it.id }) { subject ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.openSubject(subject) }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .width(48.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            val cover = subject.coverUrlSmall.withImageBase(state.imageBase)
                            if (cover.isNotEmpty()) {
                                AsyncImage(
                                    model = cover,
                                    contentDescription = subject.displayTitle,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                subject.displayTitle,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    if (subject.rating.score > 0) append("%.1f 分".format(subject.rating.score))
                                    if (subject.airDate.isNotEmpty()) {
                                        if (isNotEmpty()) append(" · ")
                                        append(subject.airDate)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("详情 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }

                val onlineOnly = state.onlineResults.filter { r -> matches.none { it.id == r.id } }

                if (state.onlineSearching) {
                    item(key = "online-loading") {
                        Text(
                            "在线搜索中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }

                if (onlineOnly.isNotEmpty()) {
                    item(key = "online-header") {
                        Text(
                            "在线结果（${onlineOnly.size}）",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(onlineOnly, key = { "online-${it.id}" }) { result ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.openSubject(result.toSubject()) }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.width(12.dp))
                            val onlineCover = listOf(
                                result.images.common,
                                result.images.medium,
                                result.images.large,
                            ).firstOrNull { it.isNotBlank() }.orEmpty().withImageBase(state.imageBase)
                            Box(
                                Modifier
                                    .width(48.dp)
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                if (onlineCover.isNotEmpty()) {
                                    AsyncImage(
                                        model = onlineCover,
                                        contentDescription = result.displayTitle,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    result.displayTitle,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    buildString {
                                        if (result.rating.score > 0) append("%.1f 分".format(result.rating.score))
                                        val extra = listOfNotNull(
                                            result.date.takeIf { it.isNotEmpty() },
                                            result.platform.takeIf { it.isNotEmpty() },
                                        ).joinToString(" · ")
                                        if (extra.isNotEmpty()) {
                                            if (isNotEmpty()) append(" · ")
                                            append(extra)
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "播放",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        } else {
            val ok = state.hits.count { it.done && it.error == null && it.items.isNotEmpty() }
            val empty = state.hits.count { it.done && it.error == null && it.items.isEmpty() }
            val failed = state.hits.count { it.error != null }
            val shown = if (state.showFailedSources) state.hits else state.hits.filter { it.items.isNotEmpty() }

            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "原始搜源",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "返回搜索",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { vm.updateKeyword(keyword) },
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.busy) "搜片中 ${state.completed}/${state.total}"
                        else "可用 $ok · 空 $empty · 失败 $failed",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (state.showFailedSources) "只看可用" else "展开全部",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { vm.toggleFailedSources() },
                    )
                }

                if (state.busy) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }

                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                LazyColumn(Modifier.weight(1f)) {
                    if (shown.isEmpty() && !state.busy) {
                        item {
                            Text(
                                "没有源搜到「$keyword」。可以换个别名再试，或确认规则是否还有效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    shown.forEach { hit ->
                        item(key = "source-${hit.ruleName}") { SourceHeaderRow(hit) { vm.startVerify(hit.rule) } }
                        // 站点搜索常常带回一堆无关结果：命中关键字的排前面，最多显示 8 条
                        val ordered = if (keyword.isBlank()) hit.items
                        else hit.items.sortedByDescending { it.name.contains(keyword, ignoreCase = true) }
                        itemsIndexed(
                            ordered.take(8),
                            key = { index, _ -> "${hit.ruleName}-$index" },
                        ) { _, item ->
                            ListItem(
                                headlineContent = { Text(item.name) },
                                supportingContent = {
                                    Text(item.src, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                modifier = Modifier.clickable { vm.openChapters(hit.rule, item) },
                            )
                        }
                        if (hit.items.size > 8) {
                            item(key = "more-${hit.ruleName}") {
                                Text(
                                    "还有 ${hit.items.size - 8} 条未显示",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 源的分组头：成功/空/失败都留在原位，不隐藏。 */
@Composable
private fun SourceHeaderRow(hit: SourceHit, onVerify: (() -> Unit)? = null) {
    val text = when {
        !hit.done -> "… ${hit.ruleName} · 搜索中"
        hit.error != null -> "✗ ${hit.ruleName} · ${hit.error}"
        hit.items.isEmpty() -> "○ ${hit.ruleName} · 0 条"
        else -> "✓ ${hit.ruleName} · ${hit.items.size} 条"
    }
    val color = when {
        !hit.done -> MaterialTheme.colorScheme.onSurfaceVariant
        hit.error != null -> MaterialTheme.colorScheme.error
        hit.items.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    // 403 = Cloudflare 挑战；「验证码」= 站点自己的人机校验，两者都能用 WebView 过
    val needsVerify = onVerify != null && (
        hit.error?.contains("403") == true ||
            hit.error?.contains("验证码") == true
        )

    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        if (needsVerify) {
            Text(
                "验证",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable { onVerify?.invoke() }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun VerifyScreen(vm: AppViewModel, state: UiState) {
    AndroidView(
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // UA 必须和规则请求一致，否则 cf_clearance 拿到也用不了
                settings.userAgentString = DEFAULT_UA
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        vm.onVerifyPageLoaded()
                    }
                }
                loadUrl(state.verifyUrl)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

// ------------------------------------------------------------------ 源体检

@Composable
private fun HealthScreen(vm: AppViewModel, state: UiState) {
    val context = LocalContext.current
    var keyword by remember { mutableStateOf(state.healthKeyword) }
    val ok = state.health.count { it.status == "ok" }
    val empty = state.health.count { it.status == "empty" }
    val failed = state.health.count { it.status == "error" }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = keyword,
            onValueChange = {
                keyword = it
                vm.updateHealthKeyword(it)
            },
            label = { Text("用哪部番来测") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { vm.runHealthCheck(deep = false) },
                enabled = !state.healthBusy,
                modifier = Modifier.weight(1f),
            ) { Text("浅检（只搜索）") }
            OutlinedButton(
                onClick = { vm.runHealthCheck(deep = true) },
                enabled = !state.healthBusy,
                modifier = Modifier.weight(1f),
            ) { Text("深检（+取线路）") }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.health.isEmpty()) "还没开始" else "可用 $ok · 空 $empty · 失败 $failed · 共 ${state.health.size}",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            if (state.healthBusy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else if (state.health.isNotEmpty()) {
                Text(
                    "导出报告",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, vm.healthReportText())
                        }
                        context.startActivity(Intent.createChooser(intent, "导出体检报告"))
                    },
                )
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            items(state.health.sortedBy { when (it.status) { "ok" -> 0; "empty" -> 1; else -> 2 } },
                key = { it.rule }) { h ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (h.status) {
                            "ok" -> "✓"
                            "empty" -> "○"
                            else -> "✗"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = when (h.status) {
                            "ok" -> MaterialTheme.colorScheme.primary
                            "empty" -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.width(26.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(h.rule, style = MaterialTheme.typography.bodyMedium)
                        val detail = when (h.status) {
                            "ok" -> "${h.items} 条结果" + (if (h.roads > 0) " · ${h.roads} 条线路" else "")
                            "empty" -> "0 条结果"
                            else -> h.error.orEmpty()
                        }
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (h.status == "error") MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "${h.latencyMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 季度番剧表

private val QUARTER_NAMES = listOf("冬", "春", "夏", "秋")

@Composable
private fun SeasonScreen(vm: AppViewModel, state: UiState) {
    var showPicker by remember { mutableStateOf(false) }
    val label = "${state.seasonYear} ${QUARTER_NAMES.getOrElse(state.seasonQuarter - 1) { "" }}番"

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "◀",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { vm.shiftSeason(-1) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                // 点标题直接跳到任意季度，不用一格一格翻
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showPicker = true }
                    .padding(vertical = 6.dp),
            )
            Text(
                "▶",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { vm.shiftSeason(1) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        Text(
            if (state.seasonTotal > 0) "共 ${state.seasonTotal} 部 · 已加载 ${state.seasonItems.size}" else "正在获取…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )

        state.seasonError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            gridItems(state.seasonItems, key = { it.id }) { subject ->
                Column(
                    Modifier.clickable { vm.openSubject(subject) }
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        val cover = subject.coverUrlSmall.withImageBase(state.imageBase)
                        if (cover.isNotEmpty()) {
                            AsyncImage(
                                model = cover,
                                contentDescription = subject.displayTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        subject.displayTitle,
                        style = MaterialTheme.typography.labelMedium,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            if (subject.airDate.isNotEmpty()) append(subject.airDate)
                            if (subject.rating.score > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("%.1f".format(subject.rating.score))
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (state.seasonLoading) {
                item(span = { GridItemSpan(3) }) {
                    Box(
                        Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                }
            } else if (state.seasonItems.isNotEmpty() && state.seasonItems.size < state.seasonTotal) {
                item(span = { GridItemSpan(3) }) {
                    // 滚到底自动翻页
                    LaunchedEffect(state.seasonItems.size) { vm.loadSeason(reset = false) }
                    Box(Modifier.fillMaxWidth().height(1.dp))
                }
            }
        }
    }

    if (showPicker) {
        SeasonPickerSheet(
            year = state.seasonYear,
            quarter = state.seasonQuarter,
            onPick = { y, q ->
                showPicker = false
                vm.jumpSeason(y, q)
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** 季度选择器：年份横向翻，点季度直接跳。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeasonPickerSheet(
    year: Int,
    quarter: Int,
    onPick: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val thisYear = java.time.LocalDate.now().year
    val years = remember { ((thisYear + 1) downTo 2010).toList() }
    var pickedYear by remember { mutableStateOf(if (year > 0) year else thisYear) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "选择季度",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, bottom = 10.dp),
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(years) { y ->
                    FilterChip(
                        selected = y == pickedYear,
                        onClick = { pickedYear = y },
                        label = { Text("$y") },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QUARTER_NAMES.forEachIndexed { index, name ->
                    val q = index + 1
                    val selected = pickedYear == year && q == quarter
                    OutlinedButton(
                        onClick = { onPick(pickedYear, q) },
                        modifier = Modifier.weight(1f),
                        colors = if (selected) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                    ) { Text(name) }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "冬 1–3 月 · 春 4–6 月 · 夏 7–9 月 · 秋 10–12 月",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ 我的

@Composable
private fun MineEntryRow(
    title: String,
    subtitle: String = "",
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}

@Composable
private fun DownloadsScreen(vm: AppViewModel, state: UiState) {
    val items = state.downloads
    val doneCount = items.count { it.done }
    val active = items.filter { !it.done && !it.removing }
    val totalBytes = items.sumOf { if (it.total > 0) it.total else it.bytes }
    // 按番剧分组：下载页要回答"我下了哪几部"，而不是摊一堆散装视频
    val groups = items.groupBy { it.meta.subjectId }
    val context = LocalContext.current

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${groups.size} 部 · $doneCount 集 · ${humanSize(totalBytes)}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (active.isNotEmpty()) {
                            "还有 ${active.size} 集没下完 · 存在应用私有目录"
                        } else {
                            "存在应用私有目录，卸载应用会一起删掉"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (active.any { it.paused || it.queued }) {
                    TextButton(onClick = vm::resumeAllDownloads) { Text("全部继续") }
                }
                if (items.isNotEmpty()) {
                    TextButton(onClick = vm::removeAllDownloads) { Text("全部删除") }
                }
            }
        }

        if (state.downloadTip.isNotBlank()) {
            item {
                Text(
                    state.downloadTip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        if (items.isEmpty()) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("还没有下载", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "在播放页右上角点「下载」，把正在看的那一集存到本地。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        items(groups.keys.toList(), key = { "subject$it" }) { id ->
            DownloadGroupCard(vm, state, id, groups[id].orEmpty(), context)
        }
    }
}

/**
 * 一部番对应一张卡片。
 *
 * 单集作品（剧场版 / OVA）只有一集，折叠没意义，直接把那一集摊在标题下面；
 * 多集番剧给一行汇总（已下 n/m 集 · 大小），点开看每一集。
 */
@Composable
private fun DownloadGroupCard(
    vm: AppViewModel,
    state: UiState,
    subjectId: Int,
    group: List<DownloadItem>,
    context: android.content.Context,
) {
    val meta = state.downloadSubjects[subjectId]
    val first = group.firstOrNull() ?: return
    val title = meta?.title?.ifBlank { null } ?: first.meta.subjectTitle.ifBlank { "未知番剧" }
    // 单集：元信息里就是 1 集，或者唯一这集的标题/番名写着剧场版
    val single = group.size == 1 &&
        (meta?.single == true || meta?.totalEpisodes == 1 ||
            first.meta.episodeName.contains("剧场版") || title.contains("剧场版"))
    var expanded by remember(subjectId) { mutableStateOf(!single) }

    val bytes = group.sumOf { if (it.total > 0) it.total else it.bytes }
    val done = group.count { it.done }
    val coverModel: Any? = remember(subjectId, meta?.coverFile, first.meta.cover, state.imageBase) {
        val name = meta?.coverFile
        val local = if (!name.isNullOrBlank()) File(context.filesDir, "subjects/$name") else null
        // 有本地封面就绝不走网络：离线场景下 URL 只会转圈
        if (local != null && local.exists() && local.length() > 0) local
        else first.meta.cover.withImageBase(state.imageBase).ifBlank { null }
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !single) { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (coverModel != null) {
                AsyncImage(
                    model = coverModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 52.dp, height = 72.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (single) {
                        // 集名在下面那行显示，这里只给大小和状态，避免同一句话出现两遍
                        listOf(
                            humanSize(bytes),
                            if (first.done) "已完成" else first.progressText,
                        ).filter { it.isNotBlank() }.joinToString(" · ")
                    } else {
                        val total = meta?.totalEpisodes ?: 0
                        buildString {
                            // "已下载 1/36 集" 比 "已下载 1/1 集 · 全 36 集" 好读
                            append(
                                if (total > group.size) "已下载 $done/$total 集"
                                else "已下载 $done/${group.size} 集"
                            )
                            append(" · ")
                            append(humanSize(bytes))
                            if (group.any { !it.done }) append(" · 有未完成")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!meta?.summary.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        meta.summary.replace('\n', ' ').trim(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!single) {
                Spacer(Modifier.width(6.dp))
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (single) {
            DownloadEpisodeRow(vm, first, showCover = false, hideMeta = true)
        } else if (expanded) {
            group.sortedBy { it.meta.episodeIndex }.forEach { item ->
                DownloadEpisodeRow(vm, item, showCover = false)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    }
}

@Composable
private fun DownloadEpisodeRow(
    vm: AppViewModel,
    item: DownloadItem,
    showCover: Boolean,
    hideMeta: Boolean = false,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 46.dp, end = 16.dp, top = 2.dp, bottom = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showCover && item.meta.cover.isNotBlank()) {
                AsyncImage(
                    model = item.meta.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 48.dp, height = 66.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.meta.episodeName.ifBlank { "第 ${item.meta.episodeIndex + 1} 集" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                if (!hideMeta) {
                    Text(
                        // total 未知（HLS 分片）时 progressText 已经是大小，拼两次会重复
                        listOf(
                            item.progressText,
                            humanSize(if (item.total > 0) item.total else item.bytes),
                        ).distinct().joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            TextButton(
                onClick = {
                    when {
                        item.done -> vm.playDownload(item)
                        item.running -> vm.pauseDownload(item.id)
                        else -> vm.resumeDownload(item.id)
                    }
                },
            ) {
                Text(
                    when {
                        item.done -> "播放"
                        item.running -> "暂停"
                        else -> "继续"
                    }
                )
            }
            TextButton(onClick = { vm.removeDownload(item.id) }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
        if (!item.done) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { if (item.total > 0) item.percent / 100f else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            )
        }
    }
}

@Composable
private fun MineScreen(vm: AppViewModel, state: UiState) {
    val clipboard = LocalClipboardManager.current
    var showAllRules by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) vm.showError("读取文件失败") else vm.importRules(text)
        }
    }
    val indexCount = state.home.days.sumOf { it.items.size }
    val updated = if (state.home.updatedAt > 0) {
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(state.home.updatedAt))
    } else {
        "从未"
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                Text("我的", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatChip("${state.rules.size}", "规则")
                    StatChip("$indexCount", "索引")
                    StatChip("${state.watches.size}", "追番")
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        item {
            val doneDownloads = state.downloads.count { it.done }
            val runningDownloads = state.downloads.count { it.running }
            MineEntryRow(
                title = "下载",
                subtitle = when {
                    runningDownloads > 0 -> "$runningDownloads 个正在下载"
                    doneDownloads > 0 -> "$doneDownloads 集"
                    else -> "离线看已下载的集"
                },
                onClick = vm::openDownloads,
            )
            MineEntryRow(
                title = "季度番剧",
                subtitle = "按季度翻新番",
                onClick = vm::openSeason,
            )
            MineEntryRow(
                title = "源体检",
                subtitle = "检测规则可用性",
                onClick = vm::openHealth,
            )
        }

        item { MineSection("索引") }
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "$indexCount 部 · 更新于 $updated",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.home.error?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "最近一次失败：$it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    IndexSettings(vm, state)
                }
            }
        }

        item { MineSection("规则") }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Button(
                        onClick = vm::importRuleRepo,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("导入规则源") }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            filePicker.launch(
                                arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")
                            )
                        },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("从文件导入（.json / .ndjson）") }

                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val text = clipboard.getText()?.text
                                if (text.isNullOrBlank()) vm.showError("剪贴板为空") else vm.importRules(text)
                            },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("从剪贴板") }
                        OutlinedButton(
                            onClick = vm::addDemoRule,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("示例") }
                    }

                    state.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        val visibleRules = if (showAllRules) state.rules else state.rules.take(6)
        items(visibleRules, key = { it.name }) { rule ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { vm.selectRule(rule) }
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            rule.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "api ${rule.api}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.rules.size > 6) {
            item {
                Text(
                    if (showAllRules) "收起" else "查看全部 ${state.rules.size} 条规则",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAllRules = !showAllRules }
                        .padding(vertical = 10.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        item { MineSection("外观") }
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "light" to "浅色",
                            "dark" to "暗色",
                            "system" to "跟随系统",
                        ).forEach { (mode, label) ->
                            FilterChip(
                                selected = state.themeMode == mode,
                                onClick = { vm.setThemeMode(mode) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        }

        item { MineSection("高级") }
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Button(
                        onClick = vm::openHealth,
                        enabled = state.rules.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("源体检（${state.rules.size} 条规则）") }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "对全部规则跑一遍，看哪些还能用。浅检只搜索，深检会再取一次线路。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(14.dp))

                    Text("原始搜源（调试）", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "跳过索引，直接向全部站点并发搜索，用来排查规则是否还活着。日常看番不需要它。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (state.keyword.isBlank()) {
                                vm.showError("先在搜索页输入番名")
                            } else {
                                vm.searchSources(state.keyword)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (state.keyword.isBlank()) "需要先在搜索页输入番名"
                            else "对「${state.keyword}」搜源"
                        )
                    }
                }
            }
        }

        item { MineSection("关于") }
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "番仓 ${BuildConfig.VERSION_NAME} · 自用",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "应用不内置任何片源，所有内容来自你导入的规则；番剧元数据来自 Bangumi，更新时间来自 AniList。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatChip(value: String, label: String) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MineSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun IndexSettings(vm: AppViewModel, state: UiState) {
    val clipboard = LocalClipboardManager.current
    var base by remember(state.indexBaseUrl) { mutableStateOf(state.indexBaseUrl) }
    var imageBase by remember(state.imageBase) { mutableStateOf(state.imageBase) }

    OutlinedTextField(
        value = base,
        onValueChange = { base = it },
        label = { Text("索引地址") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = imageBase,
        onValueChange = { imageBase = it },
        label = { Text("封面地址前缀（可选，如 http://host/img）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                vm.updateIndexBase(base)
                vm.updateImageBase(imageBase)
            },
            enabled = !state.home.loading,
        ) { Text("保存地址") }
        OutlinedButton(
            onClick = vm::refreshIndex,
            enabled = !state.home.loading,
        ) { Text(if (state.home.loading) "刷新中…" else "刷新索引") }
    }

    Spacer(Modifier.height(8.dp))

    OutlinedButton(
        onClick = vm::testIndexConnection,
        enabled = state.indexProbe != "测试中…",
        modifier = Modifier.fillMaxWidth(),
    ) { Text("测试连接") }

    if (state.indexProbe.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            state.indexProbe,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.indexProbe.startsWith("失败")) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }

    Spacer(Modifier.height(8.dp))

    OutlinedButton(
        onClick = {
            val text = clipboard.getText()?.text
            if (text.isNullOrBlank()) vm.showError("剪贴板为空") else vm.importIndexJson(text)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("从剪贴板导入索引 JSON") }
}

private fun indexStatus(state: UiState): String {
    val count = state.home.days.sumOf { it.items.size }
    val time = if (state.home.updatedAt > 0) {
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(state.home.updatedAt))
    } else {
        "从未"
    }
    val error = state.home.error?.let { " · 最近一次失败：$it" }.orEmpty()
    return "缓存 $count 部 · 更新于 $time$error"
}

// ------------------------------------------------------------------ 选集
@Composable
private fun ChaptersScreen(vm: AppViewModel, state: UiState) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        items(state.roads) { road ->
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(road.name, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                road.urls.indices.chunked(4).forEach { chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        chunk.forEach { index ->
                            AssistChip(
                                onClick = { vm.play(road, index) },
                                label = {
                                    Text(road.names.getOrNull(index) ?: "第${index + 1}集")
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 播放

/** 同一站点的多条规则合并成一组，避免列表里出现「稀饭动漫 / 稀饭动漫（2）」。 */
private data class SourceGroup(
    val host: String,
    val title: String,
    val entries: List<Pair<AnimeRule, SearchItem>>,
)

private fun buildSourceGroups(state: UiState): List<SourceGroup> {
    val available = state.hits.filter { it.error == null && it.items.isNotEmpty() }
    val groups = LinkedHashMap<String, MutableList<Pair<AnimeRule, SearchItem>>>()
    for (hit in available) {
        val host = hit.rule.baseUrl.trimEnd('/').lowercase().removePrefix("https://").removePrefix("http://")
        groups.getOrPut(host) { mutableListOf() }.add(hit.rule to hit.items.first())
    }
    return groups.map { (host, entries) ->
        SourceGroup(host = host, title = entries.first().first.name, entries = entries)
    }.sortedBy { it.title }
}

/** 播放页顶部的源入口：一行显示当前源 + 源数量，点击弹面板。 */
@Composable
private fun SourceBar(state: UiState, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val groupCount = buildSourceGroups(state).size
        val failedCount = state.hits.count { it.error != null || it.items.isEmpty() }
        Text(
            if (state.entryRuleName.isEmpty()) "正在找源…" else "▶ ${state.entryRuleName}",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (state.busy) "源 $groupCount · 搜索中…" else "源 $groupCount${if (failedCount > 0) " · 失败 $failedCount" else ""}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Text("▾", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSheet(vm: AppViewModel, state: UiState, onDismiss: () -> Unit) {
    val groups = remember(state.hits, state.sourceStatus) { buildSourceGroups(state) }
    val failedHits = remember(state.hits) {
        state.hits.filter { it.error != null || it.items.isEmpty() }
    }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var showFailed by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("可用源 ${groups.size}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    if (state.busy) "搜索中 ${state.completed}/${state.total}" else "搜索完成",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(groups, key = { it.host }) { group ->
                    val entry = group.entries.first()
                    val key = "${entry.first.name}|${entry.second.src}"
                    val status = state.sourceStatus[key]
                    val isCurrent = group.entries.any { it.first.name == state.entryRuleName }
                    val isExpanded = group.host in expanded

                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (status?.failed == true) {
                                        vm.probeSource(entry.first, entry.second)
                                    } else {
                                        vm.switchSource(entry.first, entry.second)
                                        onDismiss()
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                when {
                                    isCurrent -> "▶"
                                    status?.probing == true -> "⋯"
                                    status?.failed == true -> "✗"
                                    status?.ready == true -> "✓"
                                    else -> "○"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = when {
                                    isCurrent -> MaterialTheme.colorScheme.primary
                                    status?.failed == true -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.width(24.dp),
                            )

                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(group.title, style = MaterialTheme.typography.bodyLarge)
                                    if (group.entries.size > 1) {
                                        Text(
                                            " ×${group.entries.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    when {
                                        isCurrent && state.roads.isNotEmpty() ->
                                            "正在播放 · 线路 ${state.playRoadIndex + 1} · ${state.roads.getOrNull(state.playRoadIndex)?.urls?.size ?: 0} 集"

                                        status?.probing == true -> "探测线路中…"
                                        status?.failed == true -> status.error.orEmpty()
                                        status?.ready == true ->
                                            "可用 · ${status.roads.size} 条线路 · ${status.latencyMs}ms"

                                        else -> "未尝试"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        status?.failed == true -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            // 取线路阶段被 Cloudflare/验证码 拦住的源：就地给个验证入口
                            val needsVerify = status?.failed == true && (
                                status.error?.contains("403") == true ||
                                    status.error?.contains("验证码") == true
                                )
                            if (needsVerify) {
                                Text(
                                    "验证",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                        .clickable {
                                            vm.startVerify(entry.first)
                                            onDismiss()
                                        }
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }

                            Text(
                                if (isExpanded) "▴" else "▾",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable {
                                        expanded = if (isExpanded) expanded - group.host else expanded + group.host
                                        if (!isExpanded) vm.probeSource(entry.first, entry.second)
                                    }
                                    .padding(8.dp),
                            )
                        }

                        if (isExpanded) {
                            val roads = status?.roads.orEmpty()
                            if (roads.isEmpty()) {
                                Text(
                                    if (status?.probing == true) "正在探测线路…" else "暂无线路",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 64.dp, bottom = 8.dp),
                                )
                            } else {
                                roads.forEachIndexed { index, road ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                vm.playRoad(entry.first, entry.second, index)
                                                onDismiss()
                                            }
                                            .padding(start = 64.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "线路 ${index + 1} · ${road.urls.size} 集",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            if (isCurrent && state.playRoadIndex == index) "当前" else "切换",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                }

                // 失败的源也要能看见：默认折叠，展开后每条可验证/重试
                if (failedHits.isNotEmpty()) {
                    item(key = "failed-header") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showFailed = !showFailed }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "未找到 / 失败 ${failedHits.size}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (showFailed) "收起 ▴" else "展开 ▾",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    if (showFailed) {
                        items(failedHits, key = { "failed-${it.ruleName}" }) { hit ->
                            val needsVerify = hit.error?.contains("403") == true ||
                                hit.error?.contains("验证码") == true
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        hit.ruleName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        hit.error ?: "0 条结果",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (hit.error != null) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (needsVerify) {
                                    Text(
                                        "验证",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                            .clickable {
                                                vm.startVerify(hit.rule)
                                                onDismiss()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 「这一集还没更新」的提示卡：给出能做的三件事，而不是一行红字。 */
@Composable
private fun EpisodeGateCard(vm: AppViewModel, gate: EpisodeGate, onOpenSources: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            Column(
                Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (gate.reason == GateReason.NotAired) "第 ${gate.requested} 集还没播出"
                    else "第 ${gate.requested} 集源还没更新",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (gate.reason == GateReason.NotAired) "官方目前更新到第 ${gate.expected} 集"
                    else "当前源最新到第 ${gate.sourceMax} 集",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (gate.reason == GateReason.SourceBehind && gate.sourceMax > 0) {
                        FilledTonalButton(
                            onClick = vm::watchLatestAvailable,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("先看第 ${gate.sourceMax} 集", style = MaterialTheme.typography.labelMedium) }
                    }
                    OutlinedButton(
                        onClick = onOpenSources,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text("换源", style = MaterialTheme.typography.labelMedium) }
                    OutlinedButton(
                        onClick = vm::addEpisodeAlert,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text("提醒我", style = MaterialTheme.typography.labelMedium) }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "继续看当前这集",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { vm.dismissGate() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScreen(vm: AppViewModel, state: UiState) {
    val context = LocalContext.current
    val media = state.playMedia
    val road = state.roads.getOrNull(state.playRoadIndex)
    val episodes = road?.urls?.size ?: 0
    val canPrev = road != null && state.playEpisodeIndex > 0
    val canNext = road != null && state.playEpisodeIndex in 0 until (episodes - 1)

    // 注意：key 只放 URL。playStartMs 会在播放过程中不断刷新，
    // 放进 key 会导致播放器每 5 秒被重建一次。
    val exoPlayer = remember(media?.url) {
        media?.let {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(it.headers)
                .setUserAgent(DEFAULT_UA)
            val upstream = DefaultDataSource.Factory(context, httpFactory)
            // 只有「已下完」的集才挂缓存：直接读本地分片，断网也能播。
            // 在线播放不挂缓存，避免看一集就往缓存目录塞几百 MB。
            val cache = if (
                com.dmanime.app.download.DownloadUtil.completedFor(
                    state.playSubjectId,
                    state.playEpisodeIndex,
                ) != null
            ) {
                com.dmanime.app.download.DownloadUtil.playbackCache()
            } else {
                null
            }
            val factory: androidx.media3.datasource.DataSource.Factory = if (cache != null) {
                CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(upstream)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            } else {
                upstream
            }
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .build()
                .apply {
                    // 第二个参数就是起播位置：续播不用等 prepare 后再 seek
                    setMediaItem(MediaItem.fromUri(it.url), state.playStartMs.coerceAtLeast(0))
                    prepare()
                    playWhenReady = true
                }
        }
    }

    // 每 5 秒回报一次进度，首页「继续观看」靠它
    LaunchedEffect(exoPlayer) {
        val player = exoPlayer ?: return@LaunchedEffect
        while (true) {
            delay(5_000)
            runCatching {
                val duration = player.duration
                if (duration > 0) {
                    vm.saveProgress(player.currentPosition, duration)
                    // 记住当前位置：跳到详情再返回时从这里继续
                    vm.updatePlayPosition(player.currentPosition)
                }
            }
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                vm.onPlaybackError("${error.errorCodeName}：${error.message ?: "未知错误"}")
            }

            // 播完自动连播下一集
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) vm.playNextEpisode()
            }
        }
        exoPlayer?.addListener(listener)
        onDispose {
            exoPlayer?.removeListener(listener)
            exoPlayer?.release()
        }
    }

    val sniffer = remember(state.playPageUrl) {
        PageSniffer(context, onCandidate = vm::onSniffCandidate, onStatus = vm::appendLog)
    }
    LaunchedEffect(state.playPageUrl) {
        if (state.playPageUrl.isNotEmpty()) sniffer.load(state.playPageUrl)
    }
    DisposableEffect(sniffer) {
        onDispose { sniffer.destroy() }
    }

    // 起播看门狗：15 秒还嗅不到播放地址就换源，别让用户卡在"正在起播"
    LaunchedEffect(state.playPageUrl, state.playMedia) {
        if (state.playPageUrl.isEmpty() || state.playMedia != null) return@LaunchedEffect
        delay(15_000)
        if (vm.state.playMedia == null && vm.state.playPhase == PlayPhase.Starting) {
            vm.onPlaybackError("起播超时：15 秒没有找到播放地址")
        }
    }

    // 全屏：横屏 + 隐藏系统栏
    LaunchedEffect(state.playerFullscreen) {
        val act = context as? Activity ?: return@LaunchedEffect
        act.requestedOrientation = if (state.playerFullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (state.playerFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (state.playerFullscreen) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (exoPlayer != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                PlayerOverlay(
                    player = exoPlayer,
                    fullscreen = true,
                    onToggleFullscreen = { vm.setFullscreen(false) },
                    onPrev = if (canPrev) ({ vm.play(road!!, state.playEpisodeIndex - 1) }) else null,
                    onNext = if (canNext) ({ vm.play(road!!, state.playEpisodeIndex + 1) }) else null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
        return
    }

    var showSources by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (exoPlayer != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = false
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    PlayerOverlay(
                        player = exoPlayer,
                        fullscreen = false,
                        onToggleFullscreen = { vm.setFullscreen(true) },
                        onPrev = if (canPrev) ({ vm.play(road!!, state.playEpisodeIndex - 1) }) else null,
                        onNext = if (canNext) ({ vm.play(road!!, state.playEpisodeIndex + 1) }) else null,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 点了"源还没有的集数"：盖在画面上提示，而不是静默播上一集
                    state.episodeGate?.let { gate ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.84f))
                        ) {
                            EpisodeGateCard(vm, gate) { showSources = true }
                        }
                    }
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    val gate = state.episodeGate
                    if (gate != null) {
                        EpisodeGateCard(
                            vm = vm,
                            gate = gate,
                            onOpenSources = { showSources = true },
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (state.playPhase == PlayPhase.Failed) {
                                Text(
                                    "没有可用的源",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    state.error.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    when (state.playPhase) {
                                        PlayPhase.Searching -> "正在寻找可用的源…（${state.completed}/${state.total}）"
                                        PlayPhase.Starting -> "正在起播：${state.entryRuleName}"
                                        else -> "正在解析播放地址…"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            if (state.entryRuleName.isNotEmpty()) {
                SourceBar(state) { showSources = true }
            }
            if (showSources) {
                SourceSheet(vm, state) { showSources = false }
            }

            PullToRefreshBox(
                isRefreshing = state.busy,
                onRefresh = { vm.refreshSources() },
                modifier = Modifier.weight(1f),
            ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { PlayerInfoCard(vm, state) }

                if (state.playStartMs > 0) {
                    item {
                        Text(
                            "从 ${formatDuration(state.playStartMs)} 继续播放",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }
                }

                media?.note?.takeIf { it.isNotEmpty() }?.let {
                    item {
                        Text(
                            it,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (road != null && episodes > 0) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("选集", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.width(8.dp))
                            val totalEps = state.detailInfo?.totalEpisodes ?: 0
                            Text(
                                if (totalEps > episodes) "本站 $episodes 集 / 全 $totalEps 集"
                                else "${road.name} · $episodes 集",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "第 ${state.playEpisodeIndex + 1} 集",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    // 集数多时按 24 集分组，避免一次铺出上百个 chip
                    // 三态：可播 / 源未更新（官方已播）/ 还没播出
                    // 后两种只多铺一段（最多 12 个），不做无限幽灵格
                    val totalEps = state.detailInfo?.totalEpisodes ?: 0
                    val expected = state.detailInfo?.eps ?: episodes
                    val ghost = when {
                        totalEps > episodes -> minOf(totalEps, episodes + 12)
                        expected > episodes -> minOf(expected, episodes + 12)
                        else -> episodes
                    }
                    val displayCount = maxOf(episodes, ghost)
                    val perGroup = if (displayCount > 30) 24 else displayCount
                    (0 until displayCount).chunked(perGroup).forEach { group ->
                        if (displayCount > 30) {
                            item(key = "group-${group.first()}") {
                                Text(
                                    "第 ${group.first() + 1}–${group.last() + 1} 集",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                                )
                            }
                        }
                        items(group.chunked(4)) { chunk ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                chunk.forEach { index ->
                                    val available = index < episodes
                                    val current = available && index == state.playEpisodeIndex
                                    val text = road.names.getOrNull(index) ?: "${index + 1}"
                                    AssistChip(
                                        onClick = {
                                            if (available) vm.play(road, index)
                                            else vm.showGateFor(index + 1)
                                        },
                                        label = { Text(text) },
                                        colors = when {
                                            current -> AssistChipDefaults.assistChipColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )

                                            available -> AssistChipDefaults.assistChipColors()

                                            // 源还没跟上的集数：灰一点，点开是"还没更新"提示
                                            index < expected -> AssistChipDefaults.assistChipColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                            )

                                            // 官方都还没播：更淡
                                            else -> AssistChipDefaults.assistChipColors(
                                                containerColor = MaterialTheme.colorScheme.surface,
                                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
            }
        }

        // 2dp 的 WebView：必须挂在窗口上，JS 定时器才会正常跑
        key(sniffer) {
            AndroidView(
                factory = { sniffer.webView },
                modifier = Modifier.size(2.dp).alpha(0.01f),
            )
        }
    }
}

@Composable
private fun PlayerInfoCard(vm: AppViewModel, state: UiState) {
    val info = state.detailInfo
    val subject = state.detail
    // 已下载的番剧优先用本地封面：断网时播放页不该只剩一个空框
    val localCover = remember(state.playSubjectId, state.downloads.size) {
        vm.localCoverPath(state.playSubjectId)?.let { File(it) }?.takeIf { it.exists() }
    }
    val cover = localCover ?: (subject?.coverUrlSmall ?: "").withImageBase(state.imageBase)
    val total = info?.totalEpisodes ?: 0
    val aired = info?.eps ?: 0

    val meta = buildList {
        info?.rating?.score?.takeIf { it > 0f }?.let { add("%.1f 分".format(it)) }
        when {
            total > 0 && aired in 1 until total -> add("更新至 $aired 集 / 共 $total 集")
            total > 0 -> add("共 $total 集")
            aired > 0 -> add("更新至 $aired 集")
        }
    }.joinToString(" · ")

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Box(
            Modifier
                .width(62.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val hasCover = when (cover) {
                is File -> cover.exists()
                else -> (cover as String).isNotEmpty()
            }
            if (hasCover) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                subject?.displayTitle ?: state.title.substringBefore(" · "),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val jp = info?.name.orEmpty()
            if (jp.isNotBlank() && jp != subject?.displayTitle) {
                Text(
                    jp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "详情 ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { vm.openDetailFromPlayer() }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                )
                Spacer(Modifier.width(16.dp))
                val watched = state.watches.any { it.subjectId == subject?.id }
                Text(
                    if (watched) "已追番" else "＋追番",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { vm.toggleWatch() }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                )
            }
        }
    }
}

/** airingAt（秒）→ 本地时间字符串。 */
private fun formatAiring(epochSeconds: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochSeconds * 1000))

/** 还有多久：给"人话"，不给秒。 */
private fun humanizeAiring(seconds: Long): String {
    if (seconds <= 0) return "已播出"
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "还有 ${days} 天 ${hours} 小时"
        hours > 0 -> "还有 ${hours} 小时 ${minutes} 分"
        else -> "还有 ${minutes} 分"
    }
}

private fun String.withImageBase(base: String): String {    if (base.isBlank() || isEmpty()) return this
    return replace("https://lain.bgm.tv", base).replace("http://lain.bgm.tv", base)
}

/** 毫秒 → mm:ss（超过一小时显示 h:mm:ss）。 */
private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun stateLabel(state: Int): String = when (state) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> state.toString()
}
