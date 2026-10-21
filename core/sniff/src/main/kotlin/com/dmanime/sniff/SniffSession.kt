package com.dmanime.sniff

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一次嗅探会话：接收 JS bridge 上报的消息，维护候选集合。
 *
 * 纯逻辑，不依赖 WebView，所以可以在 JVM 上测。
 */
class SniffSession(private val policy: SniffPolicy = SniffPolicy()) {

    private val json = Json { ignoreUnknownKeys = true }
    private val collector = CandidateCollector(policy)
    private var mseSeen = false

    val candidates: List<SniffCandidate> get() = collector.all()

    fun accept(message: String): SniffCandidate? {
        val obj = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
        val url = runCatching { obj["url"]?.jsonPrimitive?.content }.getOrNull()?.trim().orEmpty()
        if (url.isEmpty()) return null
        val from = runCatching { obj["from"]?.jsonPrimitive?.content }.getOrNull().orEmpty()

        if (url.startsWith("mse:")) {
            mseSeen = true
            return null
        }
        return collector.offer(url, from = from)
    }

    fun best(): SniffCandidate? = collector.best()

    fun shouldStop(): Boolean = collector.shouldStop()

    fun hasMseOnly(): Boolean = mseSeen && collector.best() == null

    fun reset() {
        collector.reset()
        mseSeen = false
    }
}
