package com.dmanime.app

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在应用内把新版本的 APK 下下来。
 *
 * 为什么不直接用系统 DownloadManager：那玩意儿的进度要注册广播、还会在通知栏留一条
 * 下载记录，用户装完还得自己回 App。这里只是「下一份安装包」，几十兆，前台静静下完
 * 直接弹安装器最省事。
 *
 * 写法上先落 `.part` 再改名：中途失败/被杀不会留下一个半截的 apk，安装器看到损坏包
 * 只会报一句 `解析软件包时出现问题`，比下载失败更难排查。
 */
object UpdateDownloader {

    /** 下载到 [dest]，进度按字节回调 0..1；拿不到总长度时不回调进度。 */
    fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        if (tmp.exists()) tmp.delete()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            // GitHub 的下载会 302 到 objects.githubusercontent.com，UA 不带会被拒
            conn.setRequestProperty("User-Agent", "Komo/${BuildConfig.VERSION_NAME}")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPct = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            val pct = (copied * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            if (tmp.length() <= 0L) throw IOException("下载到 0 字节")
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("落盘失败")
        } finally {
            conn.disconnect()
        }
    }
}
