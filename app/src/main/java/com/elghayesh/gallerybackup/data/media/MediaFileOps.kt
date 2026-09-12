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
        // Callers copy a whole folder/batch in a loop (moveFolder, renameFolder, moveMediaItems,
        // ...) and rely on this function returning null for a single failed item rather than
        // throwing: an uncaught exception here (insert() rejecting a malformed RELATIVE_PATH,
        // disk full mid-copy, a transient read error on the source, storage permission revoked
        // mid-operation, ...) would otherwise abort their ENTIRE loop immediately, leaving a
        // rename/move half-applied -- some items copied to the new location but not yet trashed
        // from the old one, the rest untouched -- with no indication to the user of what actually
        // happened beyond "some content seems to have vanished". The doc comment above promises
        // "null on failure" as a hard contract, so this wraps the whole operation to guarantee it.
        try {
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
            val copiedOk = try {
                resolver.openOutputStream(newUri)?.use { out ->
                    resolver.openInputStream(item.uri)?.use { input -> input.copyTo(out) } != null
                } ?: false
            } catch (e: Exception) {
                false
            }

            if (!copiedOk) {
                // insert() already created a MediaStore row before the copy was known to fail --
                // clean it up so a failed copy doesn't leave a broken, empty entry behind.
                runCatching { resolver.delete(newUri, null, null) }
                return@withContext null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val doneValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(newUri, doneValues, null, null)
            }

            newUri
        } catch (e: Exception) {
            null
        }
    }
