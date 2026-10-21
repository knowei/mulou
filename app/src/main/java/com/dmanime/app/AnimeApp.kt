package com.dmanime.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.util.DebugLogger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * Coil 默认的 ImageLoader 在自建反代上会出现"只加载第一张、其余全灰"，
 * 而同样的 URL 用 curl 单发/并发/复用连接都正常。这里把图片网络栈收归可控：
 *
 * - 强制 HTTP/1.1：反代走 Cloudflare，h2 下的响应流行为没保障；
 * - 关闭连接复用带来的不确定性（重试 + 放宽超时）；
 * - 打开 DebugLogger，失败能在 logcat 里看到原因。
 */
class AnimeApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        scheduleAlerts()
        // 下载缓存尽早就绪：下载器写缓存、播放器读缓存，必须是同一个 SimpleCache 实例
        runCatching { com.dmanime.app.download.DownloadUtil.setup(this) }
    }

    /**
     * 每 30 分钟检查一次「更新了提醒我」的番。
     * 用源探测而不是官方时间：源到位才算"能看"。
     */
    private fun scheduleAlerts() {
        runCatching {
            val request = androidx.work.PeriodicWorkRequestBuilder<AlertWorker>(
                30, java.util.concurrent.TimeUnit.MINUTES
            ).build()
            androidx.work.WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(
                    "episode-alerts",
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor { chain ->
                    val request = chain.request()
                    // 反代会把条件头转给上游，上游回 304 后整条链路变成 400。
                    // 这里主动去掉，缓存仍由 Coil 的磁盘缓存按 max-age 处理。
                    val cleaned = request.newBuilder()
                        .removeHeader("If-None-Match")
                        .removeHeader("If-Modified-Since")
                        .build()
                    val headers = cleaned.headers.joinToString("\n    ") { "${it.first}: ${it.second}" }
                    android.util.Log.d("dmanime", "IMG > ${cleaned.method} ${cleaned.url}\n    $headers")
                    val response = chain.proceed(cleaned)
                    if (!response.isSuccessful) {
                        android.util.Log.d(
                            "dmanime",
                            "IMG < ${response.code} ${response.message} for ${cleaned.url}",
                        )
                    }
                    response
                }
                .build()
        }
        .crossfade(true)
        .logger(DebugLogger())
        .build()
}
