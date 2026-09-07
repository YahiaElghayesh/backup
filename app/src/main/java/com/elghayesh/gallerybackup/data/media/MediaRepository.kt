package com.elghayesh.gallerybackup.data.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

private val MEDIA_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp",
    "mp4", "3gp", "3gpp", "mkv", "webm", "mov", "avi", "m4v",
)

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

    /**
     * Items currently sitting in MediaStore's own OS-level trash (Android 11+ only). The default
     * query used by [scanFolderTree] silently excludes IS_TRASHED rows no matter what selection
     * is passed -- that's on purpose, so the gallery itself never shows trashed items -- but it
     * also means those items never show up in [scanFolderTree]'s results to filter down for
     * MediaHub's own Trash screen. This asks for the opposite: only rows MediaStore has marked
     * trashed, via [MediaStore.QUERY_ARG_MATCH_TRASHED].
     */
    suspend fun scanTrashedItems(): List<MediaItem> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext emptyList()
        val queryArgs = android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val items = mutableListOf<MediaItem>()
        items += queryCollection(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            isVideo = false,
            queryArgs = queryArgs,
        )
        items += queryCollection(
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            isVideo = true,
            queryArgs = queryArgs,
        )
        items
    }

    /**
     * Files written straight to storage by something other than MediaStore's own insert() API --
     * a cloud-sync client, a cable/MTP transfer, another file manager -- can sit on disk without
     * MediaStore ever noticing them; there's no guaranteed background rescan, so a plain refresh
     * (which only re-queries MediaStore) can miss files that are plainly there. With "All files
     * access" granted, this walks the real filesystem, finds media files MediaStore doesn't know
     * about yet, and explicitly asks the system to scan just those, so the next [scanFolderTree]
     * picks them up. A no-op without that permission -- there's no way to discover them otherwise.
     */
    suspend fun rescanUnindexedMedia() = withContext(Dispatchers.IO) {
        if (!hasAllFilesAccess()) return@withContext
        val indexed = indexedAbsolutePaths()
        val missing = mutableListOf<String>()
        val root = Environment.getExternalStorageDirectory()
        fun walk(dir: File, isRoot: Boolean) {
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (isRoot && child.name == "Android") continue
                    walk(child, false)
                } else if (child.extension.lowercase() in MEDIA_EXTENSIONS && child.absolutePath !in indexed) {
                    missing += child.absolutePath
                }
            }
        }
        walk(root, true)
        if (missing.isEmpty()) return@withContext
        suspendCancellableCoroutine<Unit> { cont ->
            var remaining = missing.size
            MediaScannerConnection.scanFile(context, missing.toTypedArray(), null) { _, _ ->
                remaining--
                if (remaining <= 0 && cont.isActive) cont.resume(Unit)
            }
        }
    }

    /** Absolute filesystem paths of every image/video MediaStore currently has indexed. */
    private fun indexedAbsolutePaths(): Set<String> {
        val result = mutableSetOf<String>()
        val storageRoot = Environment.getExternalStorageDirectory().path.trimEnd('/')
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        for (collection in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            val projection = buildList {
                add(MediaStore.MediaColumns.DISPLAY_NAME)
                if (useRelativePath) {
                    add(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    @Suppress("DEPRECATION")
                    add(MediaStore.MediaColumns.DATA)
                }
            }.toTypedArray()
            context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                if (useRelativePath) {
                    val relCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        val rel = cursor.getString(relCol) ?: ""
                        result += "$storageRoot/$rel${cursor.getString(nameCol) ?: ""}"
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    while (cursor.moveToNext()) {
                        cursor.getString(dataCol)?.let { result += it }
                    }
                }
            }
        }
        return result
    }

    /**
     * Every real directory under external storage, regardless of whether MediaStore has indexed
     * any media in it -- unlike [scanFolderTree], which only sees folders MediaStore already
     * knows about. Meant for folder pickers that want "the whole filesystem" (e.g. picking a
     * folder to back up before it has anything in it yet), not the main gallery, which should
     * keep showing only folders that currently have media.
     *
     * Without "All files access" granted (see [com.elghayesh.gallerybackup.data.media.hasAllFilesAccess]),
     * scoped storage blocks raw directory listing outside this app's own sandbox, so this
     * silently returns whatever it can see -- typically nothing -- rather than throwing.
     */
    suspend fun listAllDeviceFolderPaths(): Set<String> = withContext(Dispatchers.IO) {
        val result = mutableSetOf<String>()
        fun walk(dir: File, relativePath: String) {
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (child in children) {
                if (!child.isDirectory || child.name.startsWith(".")) continue
                // Android/data and Android/obb are other apps' private storage -- inaccessible
                // even with All files access on modern Android, and not something to back up.
                if (relativePath.isEmpty() && child.name == "Android") continue
                val childPath = if (relativePath.isEmpty()) child.name else "$relativePath/${child.name}"
                result += childPath
                walk(child, childPath)
            }
        }
        walk(Environment.getExternalStorageDirectory(), "")
        result
    }

    private fun queryCollection(
        collection: android.net.Uri,
        isVideo: Boolean,
        queryArgs: android.os.Bundle? = null,
    ): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.DATE_TAKEN)
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

        context.contentResolver.query(collection, projection, queryArgs, null)?.use { cursor: Cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val dateTakenCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
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
                // DATE_TAKEN is milliseconds (unlike DATE_MODIFIED, which is seconds) and can be
                // absent/zero -- not every video has a real capture time -- so this falls back to
                // dateModifiedSec rather than ever storing a bogus/zero "date taken".
                val dateModifiedSec = cursor.getLong(dateCol)
                val dateTakenMs = if (dateTakenCol >= 0) cursor.getLong(dateTakenCol) else 0L
                val dateTakenSec = if (dateTakenMs > 0) dateTakenMs / 1000 else dateModifiedSec
                result += MediaItem(
                    id = id,
                    uri = uri,
                    displayName = cursor.getString(nameCol) ?: "",
                    folderPath = folderPath,
                    dateModifiedSec = dateModifiedSec,
                    dateTakenSec = dateTakenSec,
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
