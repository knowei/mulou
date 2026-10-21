package com.dmanime.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import androidx.media3.exoplayer.scheduler.Requirements
import com.dmanime.app.DEFAULT_UA
import com.dmanime.app.CookieStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 一集下载的元信息。
 *
 * 存进 DownloadRequest.data 跟着下载任务一起落库，这样即使进程被杀、
 * 重启后只看一条 Download 记录也能还原出「这是哪部番的第几集、封面是什么」。
 */
@Serializable
data class DownloadMeta(
    val subjectId: Int = 0,
    val episodeIndex: Int = 0,
    val episodeName: String = "",
    val subjectTitle: String = "",
    val cover: String = "",
    val url: String = "",
    /** 播放这一集需要的请求头（Referer / UA 等），下载时原样复用。 */
    val headers: Map<String, String> = emptyMap(),
)

/** 给界面用的下载条目。 */
data class DownloadItem(
    val id: String,
    val meta: DownloadMeta,
    val state: Int,
    val percent: Int,
    val bytes: Long,
    val total: Long,
    val failureReason: Int,
) {
    val done: Boolean get() = state == Download.STATE_COMPLETED
    val running: Boolean get() = state == Download.STATE_DOWNLOADING
    val paused: Boolean get() = state == Download.STATE_STOPPED
    val queued: Boolean get() = state == Download.STATE_QUEUED
    val failed: Boolean get() = state == Download.STATE_FAILED
    val removing: Boolean get() = state == Download.STATE_REMOVING

    /** 进度百分比文案：未知总长度时显示已下载量。 */
    val progressText: String
        get() = when {
            done -> "已完成"
            failed -> "失败"
            else -> if (total > 0) "$percent%" else humanSize(bytes)
        }
}

fun humanSize(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.0f MB".format(bytes / 1024.0 / 1024)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
}

/**
 * 媒体3 的离线下载。
 *
 * 单例持有 SimpleCache 与 DownloadManager：缓存目录既是下载的落盘位置，
 * 也是播放器读缓存的地方，两边必须是同一个 SimpleCache 实例（否则同一份文件会被打开两次）。
 */
@OptIn(UnstableApi::class)
object DownloadUtil {

    const val CHANNEL_ID = "downloads"
    const val CHANNEL_NAME = "下载"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val io: ExecutorService = Executors.newFixedThreadPool(2)

    private lateinit var appContext: Context
    private lateinit var cookies: CookieStore
    lateinit var cache: SimpleCache
        private set
    lateinit var manager: DownloadManager
        private set
    lateinit var notifier: DownloadNotificationHelper
        private set

    val ready: Boolean get() = this::manager.isInitialized

    fun setup(context: Context) {
        if (ready) return
        appContext = context.applicationContext
        cookies = CookieStore(appContext)

        val provider = StandaloneDatabaseProvider(appContext)
        // 放外部私有目录：卸载才清，系统清缓存不会顺手删掉几十集视频
        val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "downloads")
        if (!dir.exists()) dir.mkdirs()

        cache = SimpleCache(dir, NoOpCacheEvictor(), provider)
        notifier = DownloadNotificationHelper(appContext, CHANNEL_ID)
        ensureChannel(appContext)

        manager = DownloadManager(
            appContext,
            DefaultDownloadIndex(provider),
            DownloaderFactory { request -> createDownloader(request) },
        ).apply {
            setRequirements(Requirements(Requirements.NETWORK))
            maxParallelDownloads = 2
            minRetryCount = 3
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    /** 下载器工厂：每个任务按自己的 headers 建数据源，否则第三方源一律 403。 */
    private fun createDownloader(request: DownloadRequest): Downloader {
        val meta = decode(request)
        val headers = buildMap {
            putAll(meta.headers)
            if (!containsKey("Referer") && meta.url.isNotBlank()) put("Referer", meta.url)
            CookieStore(appContext).cookieFor(request.uri.toString())?.let { put("Cookie", it) }
        }
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_UA)
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(cache).setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE),
            )

        val item = request.toMediaItem()
        val isHls = request.mimeType?.contains("mpegurl", true) == true ||
            request.uri.toString().contains(".m3u8", true)
        return if (isHls) {
            HlsDownloader(item, cacheFactory, io)
        } else {
            ProgressiveDownloader(item, cacheFactory, io)
        }
    }

    /** 缓存 key：一部番一集一条，重建播放列表也能对上。 */
    fun cacheKey(subjectId: Int, episodeIndex: Int): String = "s${subjectId}e$episodeIndex"

    fun buildRequest(meta: DownloadMeta): DownloadRequest {
        val uri = android.net.Uri.parse(meta.url)
        val isHls = meta.url.contains(".m3u8", true)
        // 不要 setCustomCacheKey：HLS 下载会直接抛
        // "customCacheKey must be null for type: 2"（type 2 = HLS）。
        // 用默认 CacheKeyFactory（key = uri），播放时 CacheDataSource 也用 uri 作 key，
        // 正好对得上，已下载的集能直接读本地分片。
        return DownloadRequest.Builder(cacheKey(meta.subjectId, meta.episodeIndex), uri)
            .setMimeType(if (isHls) "application/x-mpegURL" else "video/mp4")
            .setData(json.encodeToString(DownloadMeta.serializer(), meta).toByteArray())
            .build()
    }

    fun decode(request: DownloadRequest): DownloadMeta {
        val raw = request.data ?: return DownloadMeta(url = request.uri.toString())
        return runCatching { json.decodeFromString(DownloadMeta.serializer(), String(raw)) }
            .getOrElse { DownloadMeta(url = request.uri.toString()) }
    }

    fun find(id: String): Download? = runCatching { manager.downloadIndex.getDownload(id) }.getOrNull()

    /** 全部下载记录，新的在前。 */
    fun list(): List<DownloadItem> {
        if (!ready) return emptyList()
        val out = mutableListOf<DownloadItem>()
        var cursor: DownloadCursor? = null
        try {
            cursor = manager.downloadIndex.getDownloads()
            while (cursor.moveToNext()) {
                val d = cursor.download
                out += toItem(d)
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return out.sortedByDescending { it.meta.subjectId }
    }

    fun toItem(d: Download): DownloadItem = DownloadItem(
        id = d.request.id,
        meta = decode(d.request),
        state = d.state,
        percent = d.percentDownloaded.toInt().coerceIn(0, 100),
        bytes = d.bytesDownloaded,
        total = d.contentLength,
        failureReason = d.failureReason,
    )

    /** 已下完的集：返回本地可播的缓存 key。 */
    fun completedFor(subjectId: Int, episodeIndex: Int): String? {
        if (!ready) return null
        val d = find(cacheKey(subjectId, episodeIndex)) ?: return null
        return if (d.state == Download.STATE_COMPLETED) d.request.customCacheKey else null
    }

    /** 边下边播用：同一个 SimpleCache 供播放器读取。 */
    fun playbackCache(): SimpleCache? = if (ready) cache else null
}
