package com.elghayesh.gallerybackup

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.elghayesh.gallerybackup.data.settings.SettingsRepository
import com.elghayesh.gallerybackup.sync.MediaChangeObserver
import com.elghayesh.gallerybackup.sync.SyncScheduler
import com.elghayesh.gallerybackup.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GalleryBackupApp : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        MediaChangeObserver(this, appScope).register()

        appScope.launch {
            val wifiOnly = SettingsRepository(this@GalleryBackupApp).wifiOnly.first()
            SyncScheduler.schedulePeriodicSync(this@GalleryBackupApp, wifiOnly)
        }
    }

    /**
     * Coil doesn't decode video files as images out of the box; without this, every
     * video tile in the gallery would render blank instead of a frame thumbnail.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            SyncWorker.CHANNEL_ID,
            "Backup sync",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows progress while your photos and videos are backed up to Drive."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
