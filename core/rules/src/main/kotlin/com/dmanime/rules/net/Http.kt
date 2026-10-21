package com.dmanime.rules.net

import com.dmanime.rules.model.RequestBodyTypes
import kotlinx.serialization.json.JsonElement
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class HttpRequest(
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val form: Map<String, String> = emptyMap(),
    val bodyType: String = RequestBodyTypes.NONE,
    val jsonBody: JsonElement? = null,
) {
    /** 把 query 追加到 URL 上（值做 percent-encoding）。 */
    fun resolvedUrl(): String = appendQuery(url, query)
}

data class HttpResponse(
    val status: Int,
    val body: String,
    val finalUrl: String = "",
    /** 发生过的跳转链（不含最终地址），便于排查"站点换域名了"。 */
    val redirects: List<String> = emptyList(),
)

fun interface RuleHttpClient {
    fun execute(request: HttpRequest): HttpResponse
}

/**
 * 默认实现：JDK 自带的 HttpURLConnection。
 *
 * 手动处理 3xx，而不是交给 `instanceFollowRedirects`：
 * - 默认实现不跟随跨协议（http → https）跳转，而这类站点正是"换域名/强制 https"；
 * - 302 之后 POST 要降级成 GET，默认行为不可控；
 * - 我们需要拿到跳转链，用于诊断"站点搬去了哪里"。
 */
class HttpUrlConnectionClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val maxRedirects: Int = 5,
    /**
     * 按域名提供 Cookie（Cloudflare 的 cf_clearance、验证码站点的会话）。
     * 由宿主实现，core 不认识存储细节。
     */
    private val cookieFor: (url: String) -> String? = { null },
) : RuleHttpClient {

    override fun execute(request: HttpRequest): HttpResponse {
        val chain = ArrayList<String>()
        var url = request.resolvedUrl()
        var method = request.method.uppercase()
        var sendBody = true
        var hops = 0

        while (true) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
                // 已通过的验证（cf_clearance / 会话 Cookie）随请求带上
                cookieFor(url)?.takeIf { it.isNotBlank() }?.let { cookie ->
                    setRequestProperty("Cookie", cookie)
                }
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            val payload: ByteArray? = if (sendBody) when {
                request.form.isNotEmpty() -> {
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    encodePairs(request.form).toByteArray(StandardCharsets.UTF_8)
                }

                request.jsonBody != null -> {
                    connection.setRequestProperty("Content-Type", "application/json")
                    request.jsonBody.toString().toByteArray(StandardCharsets.UTF_8)
                }

                else -> null
            } else null

            if (payload != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(payload) }
            }

            val status = connection.responseCode

            if (status in REDIRECT_CODES) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()

                if (location.isNullOrBlank() || hops >= maxRedirects) {
                    return HttpResponse(status, "", url, chain)
                }

                chain += url
                url = resolve(url, location)
                hops++

                // 303 一律改成 GET；301/302 对 POST 也按浏览器惯例降级。
                // 307/308 要求保持原方法和请求体。
                if (status == 303 || ((status == 301 || status == 302) && method == "POST")) {
                    method = "GET"
                    sendBody = false
                }
                continue
            }

            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            return HttpResponse(status, body, url, chain)
        }
    }

    private fun resolve(base: String, location: String): String {
        val trimmed = location.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val baseUri = runCatching { URI(base) }.getOrNull() ?: return trimmed
        return runCatching { baseUri.resolve(trimmed).toString() }.getOrDefault(trimmed)
    }

    private fun encodePairs(pairs: Map<String, String>): String =
        pairs.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

    /** 必须用 String 重载：`encode(String, Charset)` 需要 Android API 33。 */
    private fun encode(text: String): String =
        URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/** 追加 query 参数；已有 query 用 `&` 连接。 */
fun appendQuery(url: String, query: Map<String, String>): String {
    if (query.isEmpty()) return url
    val encoded = query.entries.joinToString("&") { (k, v) ->
        "${encodeComponent(k)}=${encodeComponent(v)}"
    }
    val separator = if (url.contains('?')) "&" else "?"
    return url + separator + encoded
}

fun encodeComponent(text: String): String =
    URLEncoder.encode(text, "UTF-8").replace("+", "%20")
