package com.dmanime.rules.engine

import com.dmanime.rules.error.CaptchaRequiredException
import com.dmanime.rules.error.HttpStatusException
import com.dmanime.rules.model.AnimeRule
import com.dmanime.rules.model.ChaptersOutcome
import com.dmanime.rules.model.SearchOutcome
import com.dmanime.rules.net.HttpRequest
import com.dmanime.rules.net.HttpUrlConnectionClient
import com.dmanime.rules.net.RuleHttpClient

/**
 * 规则执行入口：一条规则 + 一个关键词/来源 → 搜索结果 / 线路剧集。
 *
 * 只负责「发现」与「定位」两段；真正的媒体地址（m3u8）由嗅探层拿，
 * 不在这里做。
 */
class RuleEngine(
    private val http: RuleHttpClient = HttpUrlConnectionClient(),
) {
    private val xpath = XPathStrategy()
    private val api = ApiStrategy()

    fun search(rule: AnimeRule, keyword: String): SearchOutcome {
        val request = if (rule.usesApiSearch) {
            api.prepareSearchRequest(rule.searchApiConfig, mapOf("keyword" to keyword))
        } else {
            xpath.prepareSearchRequest(rule, keyword)
        }

        val response = http.execute(request)

        if (!rule.usesApiSearch && xpath.detectsCaptcha(response.body, rule.antiCrawlerConfig)) {
            throw CaptchaRequiredException(rule.name)
        }
        if (response.status !in 200..299) {
            throw HttpStatusException(response.status, request.resolvedUrl())
        }

        val (items, diagnostics) = if (rule.usesApiSearch) {
            api.parseSearch(response.body, rule.searchApiConfig)
        } else {
            xpath.parseSearch(response.body, rule)
        }
        return SearchOutcome(
            items = items,
            diagnostics = diagnostics,
            requestUrl = request.resolvedUrl(),
            rawResponse = response.body,
        )
    }

    fun chapters(rule: AnimeRule, source: String): ChaptersOutcome {
        val request = if (rule.usesApiChapters) {
            api.prepareChapterRequest(rule.chapterApiConfig, mapOf("source" to source))
        } else {
            xpath.prepareChapterRequest(rule, source)
        }

        val response = http.execute(request)
        if (response.status !in 200..299) {
            throw HttpStatusException(response.status, request.resolvedUrl())
        }

        val (roads, diagnostics) = if (rule.usesApiChapters) {
            api.parseChapters(response.body, rule.chapterApiConfig, source, rule.baseUrl)
        } else {
            xpath.parseChapters(response.body, rule)
        }
        return ChaptersOutcome(
            roads = roads,
            diagnostics = diagnostics,
            requestUrl = request.resolvedUrl(),
            rawResponse = response.body,
        )
    }

    /**
     * 抓一集播放页的 HTML，只为了看一眼里面有没有明文媒体地址（见 [MacCmsPlayer]）。
     *
     * 拿不到就返回 null——调用方把它当作"可能更快的一条路"，失败要能无声退回嗅探，
     * 所以这里不抛异常。
     */
    fun fetchPage(url: String, referer: String = ""): String? = runCatching {
        val headers = if (referer.isBlank()) emptyMap() else mapOf("Referer" to referer)
        val response = http.execute(HttpRequest(url = url, headers = headers))
        if (response.status in 200..299) response.body else null
    }.getOrNull()
}
