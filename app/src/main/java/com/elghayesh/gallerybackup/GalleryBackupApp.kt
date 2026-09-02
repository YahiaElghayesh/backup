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
        CrashReporter.install(this)
        createNotificationChannel()

        MediaChangeObserver(this, appScope).register()

        // An uncaught exception in any coroutine on this scope crashes the whole app on every
        // future launch (this exact scope's own startup work runs unconditionally on every cold
        // start) -- catch broadly here so a scheduling hiccup degrades to "sync doesn't run" the
        // Gallery can still be used, rather than a boot loop with no way back in to fix settings.
        appScope.launch {
            try {
                val wifiOnly = SettingsRepository(this@GalleryBackupApp).wifiOnly.first()
                SyncScheduler.schedulePeriodicSync(this@GalleryBackupApp, wifiOnly)
            } catch (e: Exception) {
                // Nothing to do -- periodic sync just won't be (re)scheduled this launch.
            }
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
