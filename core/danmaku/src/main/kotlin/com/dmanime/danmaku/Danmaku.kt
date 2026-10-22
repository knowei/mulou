package com.dmanime.danmaku

/**
 * 一条弹幕。时间相对视频开头（毫秒）。
 *
 * 只保留三种模式：滚动、顶部固定、底部固定。
 * B 站的逆向滚动（6）、高级弹幕（7）、代码弹幕（8）、BAS（9）一律丢弃——
 * 它们要么需要完整播放器实现，要么是给 UP 主做特效用的，在第三方片源上没意义。
 */
data class Danmaku(
    val timeMs: Long,
    val mode: Mode,
    val color: Int,
    val text: String,
    val sizePx: Int = DEFAULT_SIZE_PX,
) {
    enum class Mode { Scroll, Top, Bottom }

    companion object {
        const val DEFAULT_SIZE_PX = 25
    }
}

/** 弹幕库对一个番剧某一集的最小描述。 */
data class DanmakuEpisode(
    val index: Int,
    val title: String,
    val longTitle: String,
    /** 分 P 的弹幕库编号。 */
    val cid: Long,
    val durationMs: Long,
    /**
     * 稿件的 aid，评论区挂在它上面（弹幕挂 cid）。
     * 用户投稿那条路一个稿件有多个分 P，它们共用同一个 aid，所以评论是整条视频的。
     */
    val aid: Long = 0L,
)

/** 一部作品在 B 站的登记信息。 */
data class DanmakuSeason(
    val seasonId: Long,
    val title: String,
    val originalTitle: String,
    val episodeCount: Int,
    val score: Double,
    val cover: String,
    val typeName: String,
    val indexShow: String,
)

/**
 * B 站 UGC 区（用户自己上传）的一条搜索结果。
 *
 * 番剧库（pgc）没有的片子，往往能在 UGC 区找到搬运：UP 主把一个合集切成若干分 P，
 * 每个分 P 就是一集，每个分 P 有自己的 cid 和弹幕库。分 P 这条路径最理想——
 * 时间轴从每集开头算，不需要任何偏移。
 */
data class UgcVideo(
    val bvid: String,
    val title: String,
    val play: Int,
    val durationSeconds: Int,
)

/**
 * 弹幕来源。
 *
 * 三路各有各的覆盖范围：官方番剧库最准但只有引进的片子，UGC 区能补冷门番和国漫，
 * 弹弹play 能补前两者都没有的（还带第三方站聚合）。用户想开哪个就开哪个，
 * 多选时按时间合并。
 */
enum class DanmakuOrigin(val label: String, val shortLabel: String) {
    Catalog("哔哩哔哩番剧库", "番剧库"),
    Ugc("哔哩哔哩用户投稿", "用户投稿"),
    DanDanPlay("弹弹play", "弹弹play"),
}

fun interface DanmakuSource {
    /** 取某一集的弹幕；拿不到返回空列表。 */
    fun comments(episode: DanmakuEpisode): List<Danmaku>
}
