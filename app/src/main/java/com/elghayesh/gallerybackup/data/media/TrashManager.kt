package com.elghayesh.gallerybackup.data.media

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

sealed class DeleteResult {
    data object Deleted : DeleteResult()
    data class ConsentRequired(val pendingIntent: PendingIntent) : DeleteResult()
    data class Error(val message: String) : DeleteResult()
}

/**
 * Deletes media. On Android 11+ this uses MediaStore's real trash -- recoverable for
 * about 30 days, the same trash Google Photos/Files use -- via a one-time system
 * confirmation dialog. There is no OS-level trash API before Android 11, so on those
 * versions this falls back to permanent deletion after an in-app confirmation; that is
 * a real platform limitation, not an oversight.
 */
class TrashManager(private val context: Context) {

    fun requestDelete(uris: List<Uri>): DeleteResult {
        if (uris.isEmpty()) return DeleteResult.Deleted
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createTrashRequest(context.contentResolver, uris, true)
            DeleteResult.ConsentRequired(pendingIntent)
        } else {
            try {
                for (uri in uris) context.contentResolver.delete(uri, null, null)
                DeleteResult.Deleted
            } catch (e: Exception) {
                DeleteResult.Error(e.message ?: "Delete failed")
            }
        }
    }
}
