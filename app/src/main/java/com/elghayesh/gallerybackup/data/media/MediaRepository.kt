package com.elghayesh.gallerybackup.data.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the device's photo/video library from MediaStore and reconstructs the real
 * on-device folder structure (Google Photos-style backups intentionally throw this away;
 * this app intentionally keeps it).
 */
class MediaRepository(private val context: Context) {

    suspend fun scanFolderTree(): FolderNode = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        items += queryCollection(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            isVideo = false,
        )
        items += queryCollection(
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            isVideo = true,
        )
        FolderNode.buildTree(items)
    }

    private fun queryCollection(collection: android.net.Uri, isVideo: Boolean): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            if (useRelativePath) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.MediaColumns.DATA)
            }
            if (isVideo) add(MediaStore.Video.VideoColumns.DURATION)
        }.toTypedArray()

        context.contentResolver.query(collection, projection, null, null, null)?.use { cursor: Cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val relPathCol = if (useRelativePath)
                cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
            val dataCol = if (!useRelativePath)
                cursor.getColumnIndex(MediaStore.MediaColumns.DATA) else -1
            val durationCol = if (isVideo)
                cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1

            val storageRoot = Environment.getExternalStorageDirectory().path.trimEnd('/')

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val folderPath = if (useRelativePath) {
                    cursor.getString(relPathCol) ?: ""
                } else {
                    val fullPath = cursor.getString(dataCol) ?: ""
                    derivePathFromAbsolute(fullPath, storageRoot)
                }
                val uri = ContentUris.withAppendedId(collection, id)
                result += MediaItem(
                    id = id,
                    uri = uri,
                    displayName = cursor.getString(nameCol) ?: "",
                    folderPath = folderPath,
                    dateModifiedSec = cursor.getLong(dateCol),
                    size = cursor.getLong(sizeCol),
                    mimeType = cursor.getString(mimeCol) ?: if (isVideo) "video/*" else "image/*",
                    isVideo = isVideo,
                    durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L,
                )
            }
        }
        return result
    }

    /** Pre-Android-10 fallback: derive "DCIM/Camera" from "/storage/emulated/0/DCIM/Camera/foo.jpg". */
    private fun derivePathFromAbsolute(absolutePath: String, storageRoot: String): String {
        val withoutRoot = if (absolutePath.startsWith(storageRoot)) {
            absolutePath.removePrefix(storageRoot).trimStart('/')
        } else {
            absolutePath.trimStart('/')
        }
        val lastSlash = withoutRoot.lastIndexOf('/')
        return if (lastSlash <= 0) "" else withoutRoot.substring(0, lastSlash)
    }
}
