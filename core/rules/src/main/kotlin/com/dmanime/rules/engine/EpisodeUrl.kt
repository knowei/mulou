package com.dmanime.rules.engine

import java.net.URI

/**
 * 集数 URL 归一化，移植自 Kazumi 的 `utils/episode_url.dart`。
 *
 * 目的：同一个剧集在「播放」「下载」「历史回填」等入口得到同一个字符串，
 * 可以作为身份主键。规则：
 * - 去首尾空白，空串原样返回；
 * - 相对路径按 baseUrl 补全；
 * - 同站（同 host、同端口）的 http/https 统一到 baseUrl 的协议；
 * - 去掉 path 末尾多余的 `/`（根路径除外）；
 * - 丢掉空 query / fragment；
 * - 幂等。
 */
object EpisodeUrl {

    fun normalize(baseUrl: String, raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val baseUri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        val base: URI? = baseUri
            ?.takeIf { !it.scheme.isNullOrEmpty() && !it.host.isNullOrEmpty() }

        val rawUri = runCatching { URI(trimmed) }.getOrNull()

        val candidate: URI? = when {
            rawUri != null && !rawUri.scheme.isNullOrEmpty() && !rawUri.host.isNullOrEmpty() -> rawUri
            base != null -> runCatching { base.resolve(trimmed) }.getOrNull()
            else -> null
        }
        if (candidate == null || candidate.host.isNullOrEmpty()) return trimmed

        var resolved: URI = candidate

        // 同站 URL 的协议统一到 baseUrl 声明的协议。
        if (base != null &&
            isHttp(base.scheme) && isHttp(resolved.scheme) &&
            resolved.scheme != base.scheme &&
            resolved.host.equals(base.host, ignoreCase = true) &&
            resolved.port == base.port
        ) {
            resolved = URI(
                base.scheme, resolved.userInfo, resolved.host, resolved.port,
                resolved.path, resolved.query, resolved.fragment,
            )
        }

        var path = resolved.path ?: ""
        while (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)

        val query = resolved.query?.takeIf { it.isNotEmpty() }
        val fragment = resolved.fragment?.takeIf { it.isNotEmpty() }

        val sb = StringBuilder()
        sb.append(resolved.scheme).append("://")
        resolved.userInfo?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append('@') }
        sb.append(resolved.host)
        if (resolved.port != -1) sb.append(':').append(resolved.port)
        sb.append(path)
        query?.let { sb.append('?').append(it) }
        fragment?.let { sb.append('#').append(it) }
        return sb.toString()
    }

    private fun isHttp(scheme: String?): Boolean = scheme == "http" || scheme == "https"
}
