package com.dmanime.app

import android.content.Context

/**
 * 按域名保存验证后拿到的 Cookie。
 *
 * 主要用途：Cloudflare 的 `cf_clearance`（挑战页通过后下发）、
 * 以及需要填验证码的站点的会话 Cookie。这些 Cookie 都**绑定 IP + UA**，
 * 换网络或用不同 UA 就会失效，所以只能存下来原样复用，没法自己伪造。
 */
class CookieStore(context: Context) {

    private val prefs = context.getSharedPreferences("cookies", Context.MODE_PRIVATE)

    fun cookieFor(url: String): String? {
        val host = hostOf(url) ?: return null
        return prefs.getString(host, null)
    }

    fun save(url: String, cookie: String?) {
        val host = hostOf(url) ?: return
        if (cookie.isNullOrBlank()) {
            prefs.edit().remove(host).apply()
        } else {
            prefs.edit().putString(host, cookie).apply()
        }
    }

    fun hosts(): List<String> = prefs.all.keys.toList()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun hostOf(url: String): String? = runCatching {
        java.net.URI(url).host?.lowercase()?.removePrefix("www.")
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
