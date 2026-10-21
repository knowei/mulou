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
) {
    val hasProgress: Boolean get() = ruleName.isNotBlank() && entrySrc.isNotBlank()

    val episodeLabel: String get() = "第 ${episodeIndex + 1} 集"

    /** 0~1；没有时长信息时返回 0。 */
    val progress: Float
        get() = when {
            durationMs <= 0 -> 0f
            else -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
}

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
