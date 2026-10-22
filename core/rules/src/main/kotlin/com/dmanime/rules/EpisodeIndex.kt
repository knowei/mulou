package com.dmanime.rules

/**
 * 片源集名的解析与"第 N 集"对齐。
 *
 * 放在 core 里是为了能被单测覆盖：这段逻辑一旦错位，用户看到的是"点了第 5 集播第 4 集"
 * 这种静默错误，比报错难发现得多。
 */
object EpisodeIndex {

    /**
     * 集号：认得 "第5.5集"、"05"、"EP12" 这类写法，第一组是数字本体（可带小数）。
     *
     * 后面紧跟 K/P 的不算数字：片源标题里全是 "4K"、"1080P" 这种分辨率，
     * 当集号读会让"这条线路有没有集号"的判断失效，剧场版就再也匹配不上。
     */
    private val EPISODE_NO = Regex("""第?\s*(\d+(?:\.\d+)?)(?![KkPp\d])""")

    /**
     * 数字上限。片源集名里混着 "HD1080P"、"4K2160" 这种分辨率，
     * 当集号读会把整条线路判成"有集号"，于是剧场版就再也匹配不上了。
     * 一千集以上的番不存在，超过就认为它不是集号。
     */
    private const val MAX_PLAUSIBLE = 999

    /** 集号（可带小数）。认不出来或不像集号时返回 null。 */
    fun parseNo(name: String?): Double? {
        val text = name ?: return null
        val match = EPISODE_NO.find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return value.takeIf { it > 0 && it <= MAX_PLAUSIBLE }
    }

    /** 只认整数集号：5.5 集既不顶替第 5 集，也不顶替第 6 集。 */
    fun parseWhole(name: String?): Int? {
        val no = parseNo(name) ?: return null
        val whole = no.toInt()
        return whole.takeIf { no == it.toDouble() }
    }

    /**
     * 在线路里找第 [number] 集的下标。
     *
     * 找不到就返回 null，**绝不**退回到最后一集：那只会在源缺集时悄悄播错内容。
     *
     * 一个例外：片源整条线路都没有集号时按顺序推断。剧场版和单集作品常把唯一一集
     * 命名成「HD中字」「正片」「高清HD」，按集号找永远是空，用户看到的就是
     * "明明有集数却提示源还没更新"。只要线路集数够，第 N 集就是第 N 条。
     */
    fun match(names: List<String?>, urlCount: Int, number: Int): Int? {
        if (number < 1) return null
        names.forEachIndexed { index, name ->
            if (parseWhole(name) == number) return index
        }
        val hasAnyNumber = names.any { parseNo(it) != null }
        if (!hasAnyNumber && number <= urlCount) return number - 1
        return null
    }
}
