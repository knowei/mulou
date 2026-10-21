package com.dmanime.danmaku

/**
 * 把 Bangumi 的番剧名对到 B 站的 season。
 *
 * 匹配错比匹配不上更糟：弹幕会整集乱飘，用户还以为片子错了。
 * 所以宁可选不出来（返回 null，界面不显示弹幕），也不退而求其次挑"看起来最像"的。
 * 判据依次是：标题归一化后相等 > 一方包含另一方（且长度接近）> 集数吻合加分。
 */
object DanmakuMatcher {

    private val PUNCT = Regex("[\\s\\p{Punct}　·・、。，！？：；「」『』（）《》【】～—…]")

    /**
     * 季数 / 版本标记。两边必须一致，否则一律判为不同作品。
     *
     * 只用"包含关系"区分不出「芙莉莲」和「芙莉莲 第二季」——9 个字符里只差 3 个，
     * 相似度仍然很高。但它们的弹幕完全不是一回事，套错了整集都在刷另一部剧的梗。
     */
    private val MARKERS = listOf(
        Regex("第[一二三四五六七八九十0-9]+[季期]"),
        Regex("season[0-9]+"),
        Regex("[0-9]+(?:st|nd|rd|th)season"),
        Regex("part[0-9]+"),
        Regex("剧场版"),
        Regex("完结篇"),
        Regex("总集篇"),
        Regex("特别篇"),
        Regex("番外"),
        Regex("restart"),
    )

    fun markers(text: String): Set<String> {
        val normalized = normalize(text)
        return MARKERS.flatMapTo(LinkedHashSet()) { regex -> regex.findAll(normalized).map { it.value } }
    }

    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val c = when {
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar() // 全角 ASCII
                ch.code == 0x3000 -> ' '
                else -> ch
            }
            if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    fun score(query: String, season: DanmakuSeason, expectedEpisodes: Int = 0): Int {
        val q = normalize(query)
        if (q.isEmpty()) return -1000

        val queryMarkers = markers(query)
        var best = -1000
        for (candidate in listOf(season.title, season.originalTitle)) {
            val c = normalize(candidate)
            if (c.isEmpty()) continue
            // 季数/剧场版标记不一致：根本不是同一部，直接否掉
            if (markers(candidate) != queryMarkers) continue
            best = maxOf(best, titleScore(q, c))
        }
        if (best < 0) return best

        if (expectedEpisodes > 0 && season.episodeCount > 0) {
            best += when (val diff = kotlin.math.abs(season.episodeCount - expectedEpisodes)) {
                0 -> 15
                1 -> 5
                in 2..3 -> -5
                else -> -25
            }
        }
        return best
    }

    private fun titleScore(q: String, c: String): Int {
        if (q == c) return 100
        val shorter = if (q.length <= c.length) q else c
        val longer = if (q.length <= c.length) c else q
        if (longer.contains(shorter)) {
            // 短名被长名完整包含：长度差越大越可疑（"芙莉莲" vs "芙莉莲 第二季 完整版"）
            val gap = longer.length - shorter.length
            return when {
                gap == 0 -> 100
                gap <= 3 -> 80 - gap * 5
                gap <= 6 -> 45 - gap * 3
                else -> 10
            }
        }
        return -1000
    }

    fun best(
        query: String,
        seasons: List<DanmakuSeason>,
        expectedEpisodes: Int = 0,
        minScore: Int = 40,
    ): DanmakuSeason? = seasons
        .map { it to score(query, it, expectedEpisodes) }
        .filter { it.second >= minScore }
        .maxByOrNull { it.second }
        ?.first
}
