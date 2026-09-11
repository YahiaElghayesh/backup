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
     *
     * This is the slow half of a refresh (a full recursive directory walk, versus
     * [scanFolderTree]'s fast, already-indexed MediaStore query) and on nearly every call finds
     * nothing new -- MediaStore's own background scanner already indexes almost everything almost
     * immediately. Callers should run [scanFolderTree] first and show that result right away,
     * then call this afterward, so the common case (nothing unindexed) never delays the UI
     * reflecting what MediaStore already knows. Returns true if it found and queued anything,
     * meaning the caller should call [scanFolderTree] again afterward to pick up the newly-indexed
     * files.
     */
    suspend fun rescanUnindexedMedia(): Boolean = withContext(Dispatchers.IO) {
        if (!hasAllFilesAccess()) return@withContext false
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
        if (missing.isEmpty()) return@withContext false
        suspendCancellableCoroutine<Unit> { cont ->
            var remaining = missing.size
            MediaScannerConnection.scanFile(context, missing.toTypedArray(), null) { _, _ ->
                remaining--
                if (remaining <= 0 && cont.isActive) cont.resume(Unit)
            }
        }
        true
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
                // A fast, first-pass guess only -- MediaStore's own DATE_TAKEN if it has one, else
                // DATE_MODIFIED. This is deliberately NOT reading real EXIF here: this whole query
                // (and therefore this loop) is on the app's blocking "first paint" path, and
                // opening every single photo's file to parse its EXIF header here made a large
                // library's first scan sit on "Scanning your photos..." for a very long time. The
                // real per-file EXIF read happens afterward, in the background, via
                // refineDateTakenFromExif -- see its own doc comment.
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

    /** Successfully-read EXIF dates, by media id, kept for this repository instance's lifetime so
     * [refineDateTakenFromExif] never re-opens a file it's already resolved -- only genuinely new
     * or previously-failed items cost an actual file open on a later call. Cleared naturally when
     * the app process dies; there's no need to persist it, since it's cheap to rebuild and a photo
     * whose EXIF changed after being cached would need a fresh read anyway (which a process
     * restart or app update effectively gives it). */
    private val exifDateTakenCache = mutableMapOf<Long, Long>()

    /**
     * Re-derives [MediaItem.dateTakenSec] for every photo in [root] from its own EXIF
     * DateTimeOriginal tag, read directly from the file, replacing the fast scan's MediaStore-
     * DATE_TAKEN-or-modified-time guess.
     *
     * MediaStore's own DATE_TAKEN column is a value it cached once, during whatever scan first
     * indexed the file -- it does NOT get refreshed just because the file's actual EXIF changes
     * afterward (e.g. a desktop batch EXIF editor connected over USB/MTP rewriting a whole
     * folder's photos, which is a common way third-party tools "fix" capture dates), and in
     * practice it's also simply unreliable/absent for a lot of real photos depending on which app
     * or device produced them in the first place. Reading DateTimeOriginal directly from the
     * file's own current bytes, like this, is what desktop tools (Windows Explorer's own
     * date-taken property) and other gallery apps that get this right are actually doing -- it
     * can never go stale the way a cached database column can, since there's no cache to go stale.
     *
     * This is the slow half of a refresh -- an actual file open + EXIF header parse per photo,
     * versus [scanFolderTree]'s single indexed MediaStore query -- so callers must run it in the
     * BACKGROUND, after already showing [scanFolderTree]'s fast result, exactly like
     * [rescanUnindexedMedia]. [exifDateTakenCache] is what keeps repeated calls (e.g. on every
     * auto-refresh) cheap: only items not already resolved pay the file-open cost.
     */
    suspend fun refineDateTakenFromExif(root: FolderNode): FolderNode = withContext(Dispatchers.IO) {
        val refined = root.allItemsRecursive().map { item ->
            if (item.isVideo) {
                item
            } else {
                val exifSec = readExifDateTakenSec(item.id, item.uri, item.displayName)
                if (exifSec != null) item.copy(dateTakenSec = exifSec) else item
            }
        }
        FolderNode.buildTree(refined)
    }

    /**
     * Reads a capture date straight from EXIF, trying DateTimeOriginal first (the tag meant
     * specifically for "when the shutter opened"), then DateTimeDigitized, then plain DateTime.
     * Third-party batch EXIF-fixing tools (like the one this app's own user described using to
     * correct a whole folder's capture dates) don't all write DateTimeOriginal specifically --
     * some only touch DateTime or DateTimeDigitized, leaving DateTimeOriginal absent or stale.
     *
     * Falls back to [parseDateFromFilename] when none of those tags produce anything -- some of
     * those same batch tools instead (or additionally) rename the file itself to embed the
     * corrected date, e.g. "2016-12-30.jpg", without ever touching EXIF. A photo whose camera app
     * never wrote a DateTimeOriginal/Digitized/DateTime tag in the first place (common on cheaper
     * or older devices -- Make/Model in IFD0 can be present while the separate Exif SubIFD that
     * holds the date tags is simply absent) would otherwise keep falling through to file-modified
     * time even after a rename tool had already given it the real date, right there in its name.
     */
    private fun readExifDateTakenSec(id: Long, uri: android.net.Uri, displayName: String): Long? {
        exifDateTakenCache[id]?.let { return it }
        // Catches Throwable, not just Exception: this runs once per photo inside a map() over the
        // whole library in refineDateTakenFromExif, with no per-item isolation from its caller --
        // one photo whose EXIF trips something other than a plain Exception (a corrupt embedded
        // thumbnail causing an OutOfMemoryError, say) would otherwise abort date refinement for
        // every other photo in the library too, not just this one.
        val fromExif = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = androidx.exifinterface.media.ExifInterface(stream)
                val ms = exif.dateTimeOriginal ?: exif.dateTimeDigitized ?: exif.dateTime
                ms?.let { it / 1000 }
            }
        } catch (e: Throwable) {
            null
        }
        val result = fromExif ?: parseDateFromFilename(displayName)
        if (result != null) exifDateTakenCache[id] = result
        return result
    }

    /** yyyy-MM-dd[_ T]HH-mm-ss or yyyyMMdd_HHmmss, e.g. "2016-12-30_14-30-22", "IMG_20161230_143022". */
    private val filenameDateTimePatterns = listOf(
        Regex("""(\d{4})[-_](\d{2})[-_](\d{2})[ _T](\d{2})[-:]?(\d{2})[-:]?(\d{2})"""),
        Regex("""(\d{4})(\d{2})(\d{2})[_-](\d{2})(\d{2})(\d{2})"""),
    )

    /** yyyy-MM-dd, e.g. "2016-12-30.jpg". */
    private val filenameDateOnlyPattern = Regex("""(\d{4})-(\d{2})-(\d{2})""")

    /**
     * Recovers a capture date from [displayName] when EXIF has none to offer -- see the doc
     * comment on [readExifDateTakenSec] for why a file can genuinely have no usable EXIF date tag
     * at all. Only matches clearly date-shaped patterns (dashes/underscores as separators, an
     * explicit time component where present) and validates every field against a real calendar
     * range, so an unrelated numeric filename (a camera's IMG_1234.jpg, a download id, ...) isn't
     * misread as a date.
     */
    private fun parseDateFromFilename(displayName: String): Long? {
        val base = displayName.substringBeforeLast('.')
        for (pattern in filenameDateTimePatterns) {
            pattern.find(base)?.let { match -> filenameMatchToEpochSec(match, hasTime = true) }?.let { return it }
        }
        return filenameDateOnlyPattern.find(base)?.let { match -> filenameMatchToEpochSec(match, hasTime = false) }
    }

    private fun filenameMatchToEpochSec(match: MatchResult, hasTime: Boolean): Long? {
        val g = match.groupValues
        val year = g[1].toIntOrNull() ?: return null
        val month = g[2].toIntOrNull() ?: return null
        val day = g[3].toIntOrNull() ?: return null
        if (year !in 1990..2100 || month !in 1..12 || day !in 1..31) return null
        val hour = if (hasTime) g.getOrNull(4)?.toIntOrNull() ?: 0 else 0
        val minute = if (hasTime) g.getOrNull(5)?.toIntOrNull() ?: 0 else 0
        val second = if (hasTime) g.getOrNull(6)?.toIntOrNull() ?: 0 else 0
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        val calendar = java.util.Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }
        return calendar.timeInMillis / 1000
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
