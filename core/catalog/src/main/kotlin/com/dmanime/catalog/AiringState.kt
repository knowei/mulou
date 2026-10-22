package com.dmanime.catalog

import java.time.LocalDate
import java.time.format.DateTimeParseException

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
        val total = totalEpisodes(info)
        if (total <= 0) return false
        val start = parseDate(info.airDate) ?: return false
        if (total == 1 || info.platform == "剧场版") return !today.isBefore(start)
        val last = start.plusWeeks((total - 1).toLong())
        return today.isAfter(last)
    }

    /** 已播集数：完结作品直接算作总集数。 */
    fun airedCount(info: SubjectDetail?, today: LocalDate = LocalDate.now()): Int {
        if (info == null) return 0
        val total = totalEpisodes(info)
        if (finished(info, today)) return total
        if (total > 0) return info.eps.coerceIn(0, total)
        return info.eps
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

    fun totalEpisodes(info: SubjectDetail?): Int {
        if (info == null) return 0
        return if (info.totalEpisodes > 0) info.totalEpisodes else info.eps
    }

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
