package com.dmanime.app.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.dmanime.app.MainActivity
import com.dmanime.app.R

/**
 * 前台下载服务。
 *
 * 交给 media3 托管：任务进度、失败重试、进程重启后恢复都由它负责，
 * 我们只提供 DownloadManager 和通知。
 */
@OptIn(UnstableApi::class)
class AppDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
) {

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 4321
    }

    override fun getDownloadManager(): DownloadManager {
        DownloadUtil.setup(applicationContext)
        return DownloadUtil.manager
    }

    /** 不接 WorkManager 调度：自用场景基本是「点了就下」，退出就退出。 */
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val message = if (notMetRequirements != 0) "等待网络恢复" else null
        return DownloadUtil.notifier.buildProgressNotification(
            this,
            R.drawable.ic_stat_download,
            intent,
            message,
            downloads,
            notMetRequirements,
        )
    }
}
