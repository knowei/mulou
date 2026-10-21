package com.dmanime.app

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一条追番记录：既表示"我追了这部"，也保存"看到第几集、第几秒"。
 *
 * subjectId 是索引里的 Bangumi id；规则/条目信息用来直接续播，
 * 避免每次都重新搜一遍。
 */
@Serializable
data class WatchEntry(
    val subjectId: Int,
    val title: String,
    val cover: String = "",
    val ruleName: String = "",
    val entryName: String = "",
    val entrySrc: String = "",
    val roadIndex: Int = 0,
    val episodeIndex: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val totalEpisodes: Int = 0,
    val updatedAt: Long = 0,
    /** 上次探到的"源里一共几集"；0 表示没探到，界面就不显示状态，免得瞎说。 */
    val sourceMax: Int = 0,
    /** 上次探测的时间，用来判断要不要重探。 */
    val sourceCheckedAt: Long = 0,
) {
    val hasProgress: Boolean get() = ruleName.isNotBlank() && entrySrc.isNotBlank()

    val episodeLabel: String get() = "第 ${episodeIndex + 1} 集"

    /**
     * 追番卡片的状态：只说源里到底有没有下一集。
     *
     * 刻意不拿"官方播出时间"当判据——官方没更新完时显示「源未更新」会冤枉源，
     * 所以只在"确实有下一集"和"确实看完了"这两种确定的情况下给结论。
     */
    val nextState: WatchNextState
        get() {
            if (!hasProgress) return WatchNextState.None
            val watched = episodeIndex + 1
            val total = totalEpisodes
            return when {
                sourceMax <= 0 -> WatchNextState.None
                total > 0 && watched >= total -> WatchNextState.Finished
                sourceMax > watched -> WatchNextState.HasNext
                else -> WatchNextState.NotYet
            }
        }

    /** 0~1；没有时长信息时返回 0。 */
    val progress: Float
        get() = when {
            durationMs <= 0 -> 0f
            else -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
}

/** 追番卡片第二行的状态。 */
enum class WatchNextState { None, HasNext, Finished, NotYet }

class WatchStore(context: Context) {

    private val file = File(context.filesDir, "watches.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): List<WatchEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<WatchEntry>>(file.readText())
        }.getOrDefault(emptyList())
    }

    fun save(entries: List<WatchEntry>) {
        runCatching { file.writeText(json.encodeToString(ListSerializer(WatchEntry.serializer()), entries)) }
    }
}
