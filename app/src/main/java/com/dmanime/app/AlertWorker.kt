package com.dmanime.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dmanime.rules.engine.RuleEngine
import com.dmanime.rules.model.AnimeRule
import com.dmanime.rules.model.parseRule
import com.dmanime.rules.net.HttpUrlConnectionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 定时检查「更新了提醒我」的番：去对应站点拉一次分集，出现目标集数就发通知。
 *
 * 为什么不用官方播出时间：Bangumi 只到"星期几"，而源普遍晚几分钟到几小时；
 * 以源为准才能回答用户真正的问题——"现在能不能看"。
 */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = AlertStore(applicationContext)
        val pending = store.load().filter { !it.notified }
        if (pending.isEmpty()) return Result.success()

        val rules = loadRules()
        if (rules.isEmpty()) return Result.success()

        val engine = RuleEngine(
            HttpUrlConnectionClient(
                connectTimeoutMs = 8_000,
                readTimeoutMs = 10_000,
                cookieFor = { url -> CookieStore(applicationContext).cookieFor(url) },
            )
        )

        var changed = false
        val updated = store.load().toMutableList()
        for (alert in pending) {
            val rule = rules.firstOrNull { it.name == alert.ruleName } ?: continue
            if (rule.baseUrl.isBlank() || alert.entrySrc.isBlank()) continue
            val maxEpisode = withContext(Dispatchers.IO) {
                runCatching {
                    val outcome = engine.chapters(rule, alert.entrySrc)
                    outcome.roads.filter { it.urls.isNotEmpty() }.maxOfOrNull { it.urls.size } ?: 0
                }.getOrDefault(0)
            }
            if (maxEpisode >= alert.episode && alert.episode > 0) {
                notify(alert)
                val index = updated.indexOfFirst { it.id == alert.id }
                if (index >= 0) updated[index] = updated[index].copy(notified = true)
                changed = true
            }
        }
        if (changed) store.save(updated)
        return Result.success()
    }

    private fun loadRules(): List<AnimeRule> = runCatching {
        val file = File(applicationContext.filesDir, "rules.ndjson")
        if (!file.exists()) return emptyList()
        file.readText().lineSequence()
            .filter { it.trim().startsWith("{") }
            .mapNotNull { line -> runCatching { parseRule(line) }.getOrNull() }
            .toList()
    }.getOrDefault(emptyList())

    private fun notify(alert: EpisodeAlert) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "episode_updates"
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "更新提醒", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("alert_id", alert.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            alert.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("${alert.title} 第 ${alert.episode} 集可以看了")
            .setContentText("来自 ${alert.ruleName}，点击直接播放")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching { manager.notify(alert.id.toInt(), notification) }
    }
}
