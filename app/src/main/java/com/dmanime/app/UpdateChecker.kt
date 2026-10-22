package com.dmanime.app

import com.dmanime.update.AppVersion
import com.dmanime.update.UpdateParser
import com.dmanime.update.UpdateRelease
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 查 GitHub 上有没有新版本。
 *
 * 只看 release：tag 是给 CI 触发构建用的，release 才是「给人看的一次发布」，
 * 更新说明也写在 release 正文里。仓库还没有 release 时返回 null（当作没有新版）。
 */
object UpdateChecker {

    /** 默认查自己的仓库。调试时可以用 intent 覆盖成别的仓库来验证解析。 */
    @Volatile
    var repoOverride: String? = null

    private const val DEFAULT_REPO = "knowei/mulou"

    fun repo(): String = repoOverride ?: DEFAULT_REPO

    fun releasesPage(): String = "https://github.com/${repo()}/releases"

    /**
     * 取比 [currentVersion] 新的那一版。
     *
     * 返回 null = 已经是最新；抛 [IOException] = 网络或 HTTP 出错（界面自己决定怎么提示）。
     */
    fun latest(currentVersion: String): UpdateRelease? {
        val json = getJson("https://api.github.com/repos/${repo()}/releases?per_page=10")
            ?: return null
        val release = UpdateParser.parseLatest(json) ?: return null
        return if (AppVersion.isNewer(release.version, currentVersion)) release else null
    }

    private fun getJson(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            // GitHub API 拒绝没有 UA 的请求
            conn.setRequestProperty("User-Agent", "Komo/${BuildConfig.VERSION_NAME}")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            when (val code = conn.responseCode) {
                404 -> return null
                in 200..299 -> conn.inputStream.bufferedReader().use { it.readText() }
                else -> throw IOException("HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }
}
