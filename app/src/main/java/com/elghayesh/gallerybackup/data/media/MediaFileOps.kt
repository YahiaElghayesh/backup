package com.elghayesh.gallerybackup.data.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies [item]'s bytes into [destinationFolderPath] as a new MediaStore entry, leaving
 * the original untouched. Returns the new file's content [Uri], or null on failure.
 * "Move" is implemented elsewhere as this copy followed by trashing the original --
 * there's no single MediaStore "move" call that works uniformly across API levels.
 */
suspend fun copyMediaTo(context: Context, item: MediaItem, destinationFolderPath: String): Uri? =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = if (item.isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val defaultFolder = if (item.isVideo) "Movies" else "Pictures"
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    destinationFolderPath.ifEmpty { defaultFolder },
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val newUri = resolver.insert(collection, values) ?: return@withContext null
        val copiedOk = resolver.openOutputStream(newUri)?.use { out ->
            resolver.openInputStream(item.uri)?.use { input -> input.copyTo(out) } != null
        } ?: false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val doneValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(newUri, doneValues, null, null)
        }

        if (copiedOk) newUri else null
    }
