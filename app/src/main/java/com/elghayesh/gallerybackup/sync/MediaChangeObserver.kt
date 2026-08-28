package com.elghayesh.gallerybackup.sync

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.elghayesh.gallerybackup.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Watches MediaStore for new or changed photos/videos while this app's process is
 * alive, and kicks off a debounced backup pass when it sees one. This is a best-effort
 * fast path, not a guarantee: Android can and will kill the app's process in the
 * background, which stops this observer along with it. [SyncScheduler.schedulePeriodicSync]
 * is what actually guarantees changes get backed up eventually, observer or not.
 */
class MediaChangeObserver(
    private val context: Context,
    private val scope: CoroutineScope,
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val settings = SettingsRepository(context)

    fun register() {
        val resolver = context.contentResolver
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, this)
        resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, this)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        scope.launch {
            val isConnected = settings.isConnected.first()
            val hasSelection = settings.selectedFolders.first().isNotEmpty()
            if (!isConnected || !hasSelection) return@launch
            val wifiOnly = settings.wifiOnly.first()
            SyncScheduler.runDebouncedSyncOnChange(context, wifiOnly)
        }
    }
}
