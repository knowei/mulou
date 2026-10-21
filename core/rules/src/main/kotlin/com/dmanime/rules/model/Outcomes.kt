package com.dmanime.rules.model

/** 搜索结果条目。src 是下一步章节请求要用的来源值（URL 或内部 id）。 */
data class SearchItem(
    val name: String,
    val src: String,
)

/** 一条播放线路：urls 与 names 等长。 */
data class Road(
    val name: String,
    val urls: List<String>,
    val names: List<String>,
)

data class SearchOutcome(
    val items: List<SearchItem>,
    val diagnostics: List<String>,
    val requestUrl: String,
    val rawResponse: String,
)

data class ChaptersOutcome(
    val roads: List<Road>,
    val diagnostics: List<String>,
    val requestUrl: String,
    val rawResponse: String,
)
