package com.dmanime.rules.net

import com.dmanime.rules.model.RequestBodyTypes
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * 重定向行为用本地 HTTP server 测，不 mock：
 * 这类问题（相对 Location、POST 降级、跨协议）只有真发请求才测得出来。
 */
class HttpRedirectTest {

    private lateinit var server: HttpServer
    private var port = 0
    private val lastMethod = AtomicReference("")

    private val client = HttpUrlConnectionClient(
        connectTimeoutMs = 2_000,
        readTimeoutMs = 2_000,
        maxRedirects = 3,
    )

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/start") { exchange -> redirect(exchange, "/final") }
        server.createContext("/final") { exchange ->
            lastMethod.set(exchange.requestMethod)
            respond(exchange, 200, "final-body")
        }
        server.createContext("/dir/start") { exchange -> redirect(exchange, "final") }
        server.createContext("/dir/final") { exchange -> respond(exchange, 200, "relative-ok") }
        server.createContext("/post") { exchange ->
            lastMethod.set(exchange.requestMethod)
            redirect(exchange, "/final")
        }
        server.createContext("/loop") { exchange -> redirect(exchange, "/loop") }
        server.createContext("/cookie") { exchange ->
            respond(exchange, 200, exchange.requestHeaders.getFirst("Cookie").orEmpty())
        }
        server.start()
        port = server.address.port
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `follows 302 and exposes the redirect chain`() {
        val response = client.execute(HttpRequest(url = "http://127.0.0.1:$port/start"))

        assertEquals(200, response.status)
        assertEquals("final-body", response.body)
        assertTrue(response.finalUrl.endsWith("/final"), response.finalUrl)
        assertEquals(1, response.redirects.size)
    }

    @Test
    fun `resolves relative location headers`() {
        val response = client.execute(HttpRequest(url = "http://127.0.0.1:$port/dir/start"))

        assertEquals(200, response.status)
        assertEquals("relative-ok", response.body)
        assertTrue(response.finalUrl.endsWith("/dir/final"), response.finalUrl)
    }

    /** 301/302 之后 POST 按浏览器惯例降级为 GET，且不再发送请求体。 */
    @Test
    fun `downgrades post to get after 302`() {
        val response = client.execute(
            HttpRequest(
                method = "POST",
                url = "http://127.0.0.1:$port/post",
                form = mapOf("wd" to "test"),
                bodyType = RequestBodyTypes.FORM,
            )
        )

        assertEquals(200, response.status)
        assertEquals("GET", lastMethod.get())
    }

    @Test
    fun `stops after max redirects instead of looping forever`() {
        val response = client.execute(HttpRequest(url = "http://127.0.0.1:$port/loop"))

        assertTrue(response.status in setOf(301, 302, 303, 307, 308), "status=${response.status}")
        assertEquals(3, response.redirects.size)
    }

    /**
     * 验证拿到的 Cookie（cf_clearance / 会话）必须能被规则请求带上，
     * 否则"点了验证"对后续请求没有任何作用。
     */
    @Test
    fun `attaches cookies provided for the host`() {
        val withCookie = HttpUrlConnectionClient(
            connectTimeoutMs = 2_000,
            readTimeoutMs = 2_000,
            cookieFor = { url -> if (url.contains("127.0.0.1")) "cf_clearance=abc123" else null },
        )

        val response = withCookie.execute(HttpRequest(url = "http://127.0.0.1:$port/cookie"))

        assertEquals(200, response.status)
        assertEquals("cf_clearance=abc123", response.body.trim())
    }

    @Test
    fun `sends no cookie when provider returns null`() {
        val response = client.execute(HttpRequest(url = "http://127.0.0.1:$port/cookie"))

        assertEquals(200, response.status)
        assertEquals("", response.body.trim())
    }

    private fun redirect(exchange: HttpExchange, location: String) {
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
