package com.dmanime.catalog

/**
 * 番名的「版本标记」抽取：第几季 / 剧场版 / OVA / 特别篇。
 *
 * 为什么要单独拎出来：番名一长，列表里两行也未必放得下，末尾的「第二季」正好被省略号吃掉，
 * 于是《葬送的芙莉莲》和《葬送的芙莉莲 第二季》在搜索结果里长得一模一样，选错了才发现。
 * 抽成标签贴在标题前面，扫一眼就能分辨。
 *
 * 只做「能确定」的识别：拿不准就返回 null，宁可没有标签，也不要贴错。
 */
object TitleVersion {

    private val CN_SEASON = Regex("第\\s*([0-9]+|[一二三四五六七八九十]+)\\s*[季期]")
    private val SEASON_WORD = Regex("(?i)season\\s*(\\d+)")
    private val NTH_SEASON = Regex("(?i)(\\d+)\\s*(?:st|nd|rd|th)\\s*season")
    private val S_SHORT = Regex("(?i)(?:^|[^a-z])s(\\d+)(?:[^0-9]|$)")
    private val PART = Regex("([0-9]+)\\s*期")
    private val OVA_WORD = Regex("(?i)\\b(ova|oad)\\b")
    // 「电影 某某」「电影：某某」是剧场版的另一种写法；要求后面跟分隔符，
    // 免得把《电影少女》这类正片名字误判成剧场版
    private val MOVIE_PREFIX = Regex("^电影[\\s　:：]")

    fun seasonLabel(title: String): String? {
        if (title.isBlank()) return null
        // 繁体站点的写法（劇場版）也要认，否则日文原名那条路就漏了
        if (title.contains("剧场版") || title.contains("劇場版")) return "剧场版"
        if (MOVIE_PREFIX.containsMatchIn(title)) return "剧场版"
        if (OVA_WORD.containsMatchIn(title)) {
            return if (title.contains("OVA", ignoreCase = true)) "OVA" else "OAD"
        }
        if (title.contains("最终季") || title.contains("完結篇") || title.contains("完结篇")) return "最终季"
        if (title.contains("特別篇") || title.contains("特别篇")) return "特别篇"
        CN_SEASON.find(title)?.let { return "第${normalizeNumber(it.groupValues[1])}季" }
        SEASON_WORD.find(title)?.let { return "第${it.groupValues[1]}季" }
        NTH_SEASON.find(title)?.let { return "第${it.groupValues[1]}季" }
        S_SHORT.find(title)?.let { return "第${it.groupValues[1]}季" }
        PART.find(title)?.let { return "第${it.groupValues[1]}季" }
        return null
    }

    /** 中文数字归一成阿拉伯数字，`第三季` 和 `第3季` 在标签上应该长一样。 */
    internal fun normalizeNumber(raw: String): String {
        raw.toIntOrNull()?.let { return it.toString() }
        val digits = "零一二三四五六七八九"
        return when {
            raw == "十" -> "10"
            raw.startsWith("十") -> "1" + digits.indexOf(raw.last()).let { if (it > 0) it.toString() else "" }
            raw.length == 1 -> digits.indexOf(raw).takeIf { it > 0 }?.toString() ?: raw
            else -> raw
        }
    }
}
