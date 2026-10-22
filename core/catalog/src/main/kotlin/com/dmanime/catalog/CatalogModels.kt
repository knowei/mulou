package com.dmanime.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Weekday(
    val id: Int = 0,
    val en: String = "",
    val cn: String = "",
    val ja: String = "",
)

@Serializable
data class Rating(val score: Double = 0.0, val total: Int = 0)

@Serializable
data class Images(
    val large: String = "",
    val common: String = "",
    val medium: String = "",
    val grid: String = "",
)

@Serializable
data class CatalogSubject(
    val id: Int,
    val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    val summary: String = "",
    @SerialName("air_date") val airDate: String = "",
    @SerialName("air_weekday") val airWeekday: Int = 0,
    val rating: Rating = Rating(),
    val images: Images = Images(),
) {
    /** 中文名优先，没有就用原名。 */
    val displayTitle: String get() = nameCn.ifBlank { name }

    /** Bangumi 的图片地址是 http，Android 9+ 默认拦截明文，统一抬成 https。 */
    val coverUrl: String
        get() = pick(images.large, images.common, images.medium, images.grid)

    /**
     * 列表用的小图：原图常见 200KB~2MB，一屏十几张会打爆反代
     * （FC 单实例并发只有 1，排队后先请求的能出、后面的超时）。
     */
    val coverUrlSmall: String
        get() = pick(images.common, images.medium, images.grid, images.large)

    private fun pick(vararg candidates: String): String =
        candidates.firstOrNull { it.isNotBlank() }.orEmpty().replace("http://", "https://")
}

@Serializable
data class CalendarDay(
    val weekday: Weekday = Weekday(),
    val items: List<CatalogSubject> = emptyList(),
)

/**
 * `POST /v0/search/subjects` 的单条结果。
 *
 * 字段名和日历接口不同（`date` 而不是 `air_date`），所以单独建模。
 */
@Serializable
data class SearchResult(
    val id: Int,
    val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    val summary: String = "",
    val date: String = "",
    val platform: String = "",
    val eps: Int = 0,
    @SerialName("total_episodes") val totalEpisodes: Int = 0,
    val rating: Rating = Rating(),
    val images: Images = Images(),
) {
    val displayTitle: String get() = nameCn.ifBlank { name }

    fun toSubject(): CatalogSubject = CatalogSubject(
        id = id,
        name = name,
        nameCn = nameCn,
        summary = summary,
        airDate = date,
        rating = rating,
        images = images,
    )
}

@Serializable
data class SearchResponse(
    val data: List<SearchResult> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class SubjectTag(val name: String = "", val count: Int = 0)

@Serializable
data class Collection(
    val wish: Int = 0,
    val collect: Int = 0,
    val doing: Int = 0,
    @SerialName("on_hold") val onHold: Int = 0,
    val dropped: Int = 0,
)

/**
 * `GET /v0/subjects/{id}` 的详情。
 *
 * 日历接口只有标题/封面/评分；简介全文、标签、集数、状态都在这里，
 * 且这个接口同样走反代（通配透传，不用改函数）。
 */
@Serializable
data class SubjectDetail(
    val id: Int,
    val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    val summary: String = "",
    /**
     * 首播日。详情接口 `GET /v0/subjects/{id}` 用 `date`，日历/搜索接口用
     * `air_date` —— 这里原来只写了 `air_date`，于是详情页的 `airDate` 永远是空，
     * 连带"这部番播完了没有"一直判成没播完（约定的梦幻岛 第二季 11 集全播完，
     * 界面还写着"更新至 11 集 / 共 12 集"）。两个字段名都吃。
     */
    @SerialName("date") val airDate: String = "",
    @SerialName("air_date") val airDateAlt: String = "",
    val eps: Int = 0,
    @SerialName("total_episodes") val totalEpisodes: Int = 0,
    val platform: String = "",
    val rating: Rating = Rating(),
    val images: Images = Images(),
    val tags: List<SubjectTag> = emptyList(),
    val collection: Collection = Collection(),
) {
    val displayTitle: String get() = nameCn.ifBlank { name }

    val coverUrl: String
        get() = listOf(images.large, images.common, images.medium, images.grid)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .replace("http://", "https://")

    /** 列表/追番卡片用的小图，避免存 2 MB 原图。 */
    val coverUrlSmall: String
        get() = listOf(images.common, images.medium, images.grid, images.large)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .replace("http://", "https://")

    /** 首播日：`date` 优先，缺了再看 `air_date`。 */
    val effectiveAirDate: String get() = airDate.ifBlank { airDateAlt }

    /**
     * 实际集数。走 [AiringState.totalEpisodes]：完结番以 `eps` 为准，
     * 免得"计划 12 集、只播了 11 集"的作品永远卡在"更新至 11 / 共 12"。
     */
    val episodeCount: Int get() = AiringState.totalEpisodes(this)

    val episodeLabel: String
        get() = when {
            episodeCount > 0 -> "全 $episodeCount 集"
            eps > 0 -> "已播 $eps 集"
            else -> ""
        }
}

/** 一周七天，Bangumi 的 weekday.id 是 1=周一 … 7=周日。 */
fun List<CalendarDay>.today(isoDayOfWeek: Int): CalendarDay? =
    firstOrNull { it.weekday.id == isoDayOfWeek }

@Serializable
data class CharacterActor(
    val id: Int = 0,
    val name: String = "",
    val images: Images = Images(),
)

/** 角色 + 声优。Bangumi 的 `/v0/subjects/{id}/characters`。 */
@Serializable
data class SubjectCharacter(
    val id: Int = 0,
    val name: String = "",
    /** 主角 / 配角 / 客串 */
    val relation: String = "",
    val images: Images = Images(),
    val actors: List<CharacterActor> = emptyList(),
) {
    val imageUrl: String
        get() = listOf(images.medium, images.grid, images.common)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .replace("http://", "https://")

    val actorNames: String
        get() = actors.map { it.name }.filter { it.isNotBlank() }.joinToString("、")
}