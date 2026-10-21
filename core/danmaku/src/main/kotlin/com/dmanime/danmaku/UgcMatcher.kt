package com.dmanime.danmaku

/**
 * 把番剧名对到 UGC 区（用户上传）的搬运视频。
 *
 * 和 [DanmakuMatcher] 的区别在于标题的干净程度：
 * 番剧库的标题是运营写的（"葬送的芙莉莲"），UGC 的标题是 UP 主随手起的
 * （"【猫与龙】全12集 超清中字（未删减版）"），整体相似度会被噪音压得很低。
 * 所以这里不比对相似度，而是"标题里得有番剧名 + 分集结构得像正片"：
 *
 * 1. 归一化后标题必须**完整包含**番剧名（顺序也要对）；
 * 2. 季数 / 剧场版 / 总集篇这类版本标记两边必须一致，否则是另一部；
 * 3. 分 P 数要和集数对得上，每个分 P 的时长得是一部正片的量级。
 *
 * 第 3 条是排除"解说""速看"的关键：那些是单个长视频或一堆几分钟的切片，
 * 结构上就先出局了，不用等到弹幕时间轴错位才发现。
 */
object UgcMatcher {

    /** 单个分 P 的合理时长（秒）。低于这个多半是 PV / 切片，高于这个多半是整季塞进一个视频。 */
    private val EPISODE_MIN_SECONDS = 240
    private val EPISODE_MAX_SECONDS = 9_000

    /**
     * 多集番剧（≥8 集）单个分 P 的时长下限。
     *
     * 实测「迷宫饭」UGC 区排第一的那份搬运是 24 个分 P、每个 462 秒 —— 集数对得上，
     * 但每集只有七分半，是剪辑/加速版；拿它当弹幕源，时间轴会一路错位。
     * 长篇 TV 动画单集基本都在 12 分钟以上，用 600 秒做门槛能把这类挡掉；
     * 泡面番（3 分钟一集）会因此拿不到 UGC 弹幕，属于"宁可没有也不挂错"的取舍。
     */
    private const val FEATURE_MIN_SECONDS = 600

    /** 标题层筛选：番剧名被完整包含，且说的必须是同一季。 */
    fun titleOk(query: String, title: String): Boolean {
        val q = DanmakuMatcher.normalize(query)
        if (q.length < 2) return false
        val t = DanmakuMatcher.normalize(title)
        if (!t.contains(q)) return false
        // UP 主常把标题写成「【猫与龙】第一季 全12集」，而番剧名里不带季数；
        // seasonKey 会把"没说第几季"当成第一季，所以这种能对上，
        // 而「第二季」「剧场版」「restart」仍然判成另一部。
        return DanmakuMatcher.seasonKey(title) == DanmakuMatcher.seasonKey(query)
    }

    /**
     * 分 P 结构评分。负分表示这个视频不能拿来当弹幕源。
     *
     * @param pageDurationsSeconds 每个分 P 的时长
     * @param expectedEpisodes 番剧官方集数，未知传 0
     */
    fun structureScore(pageDurationsSeconds: List<Int>, expectedEpisodes: Int): Int {
        if (pageDurationsSeconds.isEmpty()) return -1000
        val minSeconds = if (expectedEpisodes >= 8) FEATURE_MIN_SECONDS else EPISODE_MIN_SECONDS
        if (pageDurationsSeconds.any { it < minSeconds || it > EPISODE_MAX_SECONDS }) return -1000

        val pages = pageDurationsSeconds.size
        if (expectedEpisodes <= 0) return 20
        val diff = pages - expectedEpisodes
        return when {
            diff == 0 -> 30
            diff == 1 -> 10
            // 多出来的分 P 通常是 PV / 花絮，最多容忍 3 个
            diff > 3 -> -1000
            diff > 0 -> -20
            // 少集：官方 36 集、B 站只搬了 28 集很常见（分割放送），
            // 只要数量到官方的六成就还认——具体缺哪集，取的时候还会再挡一次
            pages * 10 >= expectedEpisodes * 6 -> 10
            diff >= -3 -> -20
            else -> -1000
        }
    }

    /**
     * 按「版本标记完全一致优先、播放量次之」排序标题命中的候选。
     *
     * 播放量高说明是主要的那份搬运，但如果它多标了季数（标题写「第一季」而番剧名里没有），
     * 就该让位给标记写法更接近的那个。
     */
    fun rank(query: String, videos: List<UgcVideo>): List<UgcVideo> {
        val queryMarkers = DanmakuMatcher.seasonKey(query)
        return videos
            .filter { titleOk(query, it.title) }
            .sortedWith(
                compareByDescending<UgcVideo> { DanmakuMatcher.seasonKey(it.title) == queryMarkers }
                    .thenByDescending { it.play },
            )
    }
}
