package com.dmanime.app

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 「更新了提醒我」一条记录。
 *
 * 注意：判据是**源里出现这一集**，不是官方播出时间——用户真正关心的是"能不能看"，
 * 而源通常比官方晚几分钟到几小时。
 */
@Serializable
data class EpisodeAlert(
    val id: Long = 0,
    val subjectId: Int = 0,
    val title: String = "",
    val cover: String = "",
    val episode: Int = 0,
    val ruleName: String = "",
    val entrySrc: String = "",
    val createdAt: Long = 0,
    val notified: Boolean = false,
)

class AlertStore(context: Context) {

    private val file = File(context.filesDir, "alerts.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): List<EpisodeAlert> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString<List<EpisodeAlert>>(file.readText())
    }.getOrDefault(emptyList())

    fun save(alerts: List<EpisodeAlert>) {
        runCatching { file.writeText(json.encodeToString<List<EpisodeAlert>>(alerts)) }
    }
}
