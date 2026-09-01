package com.elghayesh.gallerybackup.sync

import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.elghayesh.gallerybackup.data.onedrive.OneDriveSettingsRepository
import com.elghayesh.gallerybackup.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Runs one backup pass: scans the device, uploads whatever changed under the user's
 * selected folders, and records the outcome. Scheduled both periodically (the
 * reliable, always-eventually-runs path) and on-demand right after a media change is
 * observed or the user taps "Back up now" (the fast path). See [SyncScheduler].
 */
class SyncWorker(
    context: android.content.Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private var lastNotifyMs = 0L

    override suspend fun doWork(): Result {
        setForeground(foregroundInfo("Backing up your photos and videos..."))

        val repository = BackupRepository(applicationContext)
        val settings = SettingsRepository(applicationContext)
        val oneDriveSettings = OneDriveSettingsRepository(applicationContext)
        val oneDriveActive = oneDriveSettings.enabled.first() && oneDriveSettings.connected.first()

        suspend fun recordResult(message: String) {
            settings.recordSyncResult(message)
            if (oneDriveActive) oneDriveSettings.recordSyncResult(message)
        }

        return try {
            val outcome = repository.sync { name -> maybeUpdateNotification(name) }
            when (outcome) {
                is SyncOutcome.Completed -> {
                    recordResult("Uploaded ${outcome.uploaded}, failed ${outcome.failed}")
                    Result.success()
                }
                SyncOutcome.NotConnected -> {
                    recordResult("Not connected to Google Drive or OneDrive")
                    Result.failure()
                }
                SyncOutcome.NothingSelected -> {
                    recordResult("No folders selected for backup")
                    Result.success()
                }
            }
        } catch (e: Exception) {
            recordResult("Error: ${e.message}")
            Result.retry()
        }
    }

    /** Notification updates are throttled -- a big library can touch this hundreds of times a minute. */
    private suspend fun maybeUpdateNotification(currentFileName: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyMs < 1000L) return
        lastNotifyMs = now
        setForeground(foregroundInfo("Uploading: $currentFileName"))
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Gallery Backup")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "gallery_backup_sync"
        const val NOTIFICATION_ID = 42
    }
}
