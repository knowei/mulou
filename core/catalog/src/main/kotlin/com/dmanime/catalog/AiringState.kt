package com.dmanime.catalog

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * 「这集播了没有」的判断，不只看 Bangumi 的 `eps`。
 *
 * `eps` 是用户手动勾的，慢几天甚至漏掉都常见：实测「猫与龙」2026-07 就播完了，
 * `eps` 还停在 11，于是界面上出现"更新至 11 集 / 共 12 集"这种和"已经完结"矛盾的写法。
 * 所以再叠一条算术规则兜底——首播日 + 每周一集推末集日期，过了这天就按完结处理。
 *
 * 只处理「TV 每周一集」和「剧场版/单集」两种最常见情况；其余（不定期、分季拆开、
 * 首播日缺失）拿不准就不下结论，仍旧相信 `eps`。
 */
object AiringState {

    fun finished(info: SubjectDetail?, today: LocalDate = LocalDate.now()): Boolean {
        if (info == null) return false
        val total = plannedEpisodes(info)
        if (total <= 0) return false
        val start = parseDate(info.effectiveAirDate) ?: return false
        if (total == 1 || info.platform == "剧场版") return !today.isBefore(start)
        val last = start.plusWeeks((total - 1).toLong())
        return today.isAfter(last)
    }

    /** 已播集数：完结作品直接算作总集数。 */
    fun airedCount(info: SubjectDetail?, today: LocalDate = LocalDate.now()): Int {
        if (info == null) return 0
        val total = totalEpisodes(info)
        if (finished(info, today)) return total
        val byDate = airedByDate(info, today)
        // 有首播日就先信日期：Bangumi 的 `eps` 常常是"计划集数"而不是"已播集数"，
        // 「乱世千金…」25 集的片子 10-06 才开播，`eps` 已经写满 25，选集里全亮着。
        val base = if (byDate > 0) byDate else info.eps
        return if (total > 0) base.coerceIn(0, total) else base
    }

    /**
     * 按首播日推算已播集数（0 = 推不出来）。
     *
     * 只认「首播日 + 每周一集」这一种最常见的排期；首播日缺失、总集数未知、
     * 或者平台不是 TV/剧场版（OVA、网络放送多是攒着一次上）都不猜。
     */
    private fun airedByDate(info: SubjectDetail, today: LocalDate): Int {
        if (totalEpisodes(info) <= 0) return 0
        val start = parseDate(info.effectiveAirDate) ?: return 0
        if (totalEpisodes(info) == 1 || info.platform == "剧场版") {
            return if (today.isBefore(start)) 0 else 1
        }
        if (info.platform.isNotEmpty() && info.platform != "TV") return 0
        if (today.isBefore(start)) return 0
        return (ChronoUnit.WEEKS.between(start, today) + 1).toInt().coerceAtLeast(1)
    }

    /**
     * 已播到第几集（0 = 不知道）。
     *
     * 三个信号按可信度排：AniList 的「下一集」（`nextAiring`）只在这部番**还没播完**
     * 时才有，它给出的「下一集 - 1」最可靠；其次是首播日推出来的周数（[airedCount]）；
     * 最后才是 Bangumi 的 `eps`（用户手动勾的，常见整季都勾满，东京复仇者 三天战争篇
     * 才播到第 3 集，`eps` 已经是 13）。都没有就返回 0，界面上宁可不设闸门也不瞎猜。
     */
    fun latestEpisode(
        info: SubjectDetail?,
        nextAiring: Int = 0,
        today: LocalDate = LocalDate.now(),
    ): Int {
        if (info == null) return 0
        if (finished(info, today)) return totalEpisodes(info)
        val guess = if (nextAiring > 1) nextAiring - 1 else 0
        val byDate = airedCount(info, today)
        // 两个都有就取小的：哪个说"还没播"就按还没播算，别把没播的集放出来
        if (guess > 0 && byDate > 0) return minOf(guess, byDate)
        if (guess > 0) return guess
        return byDate.coerceAtLeast(0)
    }

    /** 选集按钮/信息卡上的那句集数说明。 */
    fun summary(
        info: SubjectDetail?,
        sourceEpisodes: Int = 0,
        today: LocalDate = LocalDate.now(),
    ): String {
        if (info == null) return if (sourceEpisodes > 0) "$sourceEpisodes 集" else ""
        val total = totalEpisodes(info)
        val aired = airedCount(info, today)
        return when {
            total > 0 && aired in 1 until total -> "更新至 $aired 集 / 共 $total 集"
            total > 0 -> "共 $total 集"
            aired > 0 -> "已更新 $aired 集"
            sourceEpisodes > 0 -> "$sourceEpisodes 集"
            else -> ""
        }
    }

    /** Bangumi 上标的集数（`total_episodes`），是"计划集数"。 */
    fun plannedEpisodes(info: SubjectDetail?): Int {
        if (info == null) return 0
        return if (info.totalEpisodes > 0) info.totalEpisodes else info.eps
    }

    /**
     * 总集数。这里只信 Bangumi 的字段，**不做推断**：
     * `total_episodes` 是计划集数，`eps` 是已播话数，两者不一致时（「约定的梦幻岛
     * 第二季」计划 12 集、实际只播 11 集）谁对谁错光看这两个数分不出来，
     * 只能靠 `/v0/episodes` 的条数校正——那一步在 CatalogRepository.episodeCountOf。
     */
    fun totalEpisodes(info: SubjectDetail?): Int = plannedEpisodes(info)

    fun parseDate(raw: String): LocalDate? {
        val text = raw.trim().take(10)
        if (text.isEmpty()) return null
        return try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
