package com.dmanime.app.download

import android.content.Context
import com.dmanime.app.DEFAULT_UA
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 番剧级元信息（标题 / 简介 / 封面），跟着下载一起落盘。
 *
 * 下载的目的是"没网也能看"，那图片和简介也得在本地，否则离线时列表里只剩
 * 一行没有封面的文字。这里按 subjectId 存一份 JSON + 一张封面图。
 */
@Serializable
data class SubjectMeta(
    val id: Int = 0,
    val title: String = "",
    val originalTitle: String = "",
    val summary: String = "",
    val coverUrl: String = "",
    val coverFile: String = "",
    val totalEpisodes: Int = 0,
    val airDate: String = "",
    val rating: Double = 0.0,
    val tags: List<String> = emptyList(),
) {
    /** 单集作品（剧场版 / OVA 一集完结）：下载页按"电影"处理，不折叠。 */
    val single: Boolean get() = totalEpisodes == 1
}

object SubjectMetaStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private fun dir(context: Context): File = File(context.filesDir, "subjects").apply { if (!exists()) mkdirs() }

    fun metaFile(context: Context, id: Int): File = File(dir(context), "$id.json")

    /** 本地封面文件（可能不存在）。 */
    fun coverFile(context: Context, id: Int): File = File(dir(context), "$id.jpg")

    fun save(context: Context, meta: SubjectMeta) {
        runCatching { metaFile(context, meta.id).writeText(json.encodeToString(SubjectMeta.serializer(), meta)) }
    }

    fun load(context: Context, id: Int): SubjectMeta? {
        val file = metaFile(context, id)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(SubjectMeta.serializer(), file.readText())
        }.getOrNull()
    }

    fun all(context: Context): List<SubjectMeta> = runCatching {
        dir(context).listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString(SubjectMeta.serializer(), f.readText()) }.getOrNull()
            }
            .orEmpty()
    }.getOrDefault(emptyList())

    /**
     * 已经把封面存下来了吗？返回可直接给 Coil 的 File。
     * 有文件就返回 File，否则 null（界面上退回网络 URL）。
     */
    fun localCover(context: Context, id: Int): File? =
        coverFile(context, id).takeIf { it.exists() && it.length() > 0 }

    fun remove(context: Context, id: Int) {
        runCatching { metaFile(context, id).delete() }
        runCatching { coverFile(context, id).delete() }
    }

    /**
     * 补全并落盘：没存过就写 JSON，封面没下过就把图拉下来。
     * 调用方应在 IO 线程执行。
     */
    fun ensure(context: Context, meta: SubjectMeta) {
        val old = load(context, meta.id)
        val merged = if (old == null) meta else meta.copy(
            coverFile = old.coverFile.ifBlank { meta.coverFile },
            summary = meta.summary.ifBlank { old.summary },
            tags = if (meta.tags.isEmpty()) old.tags else meta.tags,
            rating = if (meta.rating > 0) meta.rating else old.rating,
            airDate = meta.airDate.ifBlank { old.airDate },
        )
        save(context, merged)
        if (merged.coverUrl.isNotBlank() && localCover(context, merged.id) == null) {
            fetchCover(context, merged.id, merged.coverUrl)
        }
    }

    /** 拉封面。失败就当没有，不影响下载本身。 */
    fun fetchCover(context: Context, id: Int, url: String): File? {
        if (url.isBlank()) return null
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_UA)
                .header("Referer", url)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val file = coverFile(context, id)
                body.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                if (file.length() <= 0) {
                    file.delete()
                    return null
                }
                val meta = load(context, id)
                if (meta != null) save(context, meta.copy(coverFile = file.name))
                file
            }
        }.getOrNull()
    }
}
