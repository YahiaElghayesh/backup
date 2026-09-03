package com.elghayesh.gallerybackup

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkManager
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
        initializeWorkManager()
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
     * WorkManager's default self-initialization is disabled in the manifest (see the comment on
     * the androidx.startup provider there) because it normally runs inside a ContentProvider --
     * created by Android before this Application object even exists, let alone before
     * CrashReporter is installed above. If that initialization ever throws (e.g. a corrupted
     * persisted job left over from a previous crash), the result is a crash on every single
     * launch that never reaches any crash handler of ours, with no way back in short of
     * clearing all app data. Doing it here instead means it happens after CrashReporter is
     * installed, and it's guarded directly: if it still fails, background sync just won't work
     * this session rather than the whole app refusing to open.
     */
    private fun initializeWorkManager() {
        try {
            WorkManager.initialize(this, Configuration.Builder().build())
        } catch (e: Exception) {
            // Nothing more to do -- WorkManager-backed features (periodic/on-demand sync) won't
            // work this session, but the rest of the app remains usable.
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
