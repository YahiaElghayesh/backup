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
import kotlinx.coroutines.withTimeoutOrNull
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

    private val dateTakenDao = com.elghayesh.gallerybackup.data.db.BackupDatabase.get(context).dateTakenDao()

    suspend fun scanFolderTree(): FolderNode = withContext(Dispatchers.IO) {
        val dateTakenOverrides = dateTakenDao.getAll().associate { it.mediaId to it.dateTakenSec }
        val items = mutableListOf<MediaItem>()
        items += queryCollection(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            isVideo = false,
            dateTakenOverrides = dateTakenOverrides,
        )
        items += queryCollection(
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            isVideo = true,
            dateTakenOverrides = dateTakenOverrides,
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
        // MediaScannerConnection's own callback isn't guaranteed to fire for every path -- a
        // flaky connection to the system media scanner service, a scan that silently drops one
        // file, or OEM-specific scanner quirks (some Android skins throttle/kill scanning
        // services more aggressively than stock Android) can all leave `remaining` stuck above
        // zero forever. Without a timeout, that hangs this whole suspend call indefinitely --
        // which hangs the refresh() coroutine that called it, which means refineDateTakenFromExif
        // (further down the same coroutine) never runs at all, on any future refresh either,
        // since every refresh() re-enters this same wait. A photo's date can be read perfectly
        // correctly and still never make it into the gallery's sort order if this step never lets
        // the rest of the pipeline proceed.
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                var remaining = missing.size
                MediaScannerConnection.scanFile(context, missing.toTypedArray(), null) { _, _ ->
                    remaining--
                    if (remaining <= 0 && cont.isActive) cont.resume(Unit)
                }
            }
        }
        true
    }

    /**
     * Renames the real on-disk folder at [folderPath] (relative to external storage root) to
     * [newName] via a single atomic [File.renameTo] -- moving the whole subtree (every nested
     * file and folder) in one OS call, with no per-item copying and no MediaStore trash-consent
     * step. This matters because the previous approach (copy every item into a new path, then
     * request MediaStore trash the originals) could leave BOTH the old and new folder behind, in
     * full, if the user dismissed or the system denied that trash-consent dialog partway through
     * -- the copy had already unconditionally completed by that point, with nothing to undo it.
     * A real rename has no such window: it either fully succeeds or leaves the original
     * completely untouched, never both existing at once.
     *
     * Requires All files access (MANAGE_EXTERNAL_STORAGE) -- returns false without touching
     * anything if it isn't granted or the rename otherwise fails, so the caller can fall back to
     * the old copy+trash approach for that rarer case.
     */
    suspend fun renameFolderInPlace(folderPath: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        if (!hasAllFilesAccess()) return@withContext false
        val root = Environment.getExternalStorageDirectory()
        val oldDir = File(root, folderPath)
        val parentPath = folderPath.substringBeforeLast('/', "")
        val newFolderPath = if (parentPath.isEmpty()) newName else "$parentPath/$newName"
        val newDir = File(root, newFolderPath)
        if (!oldDir.isDirectory || newDir.exists()) return@withContext false
        if (!oldDir.renameTo(newDir)) return@withContext false

        // MediaStore's cached rows for every file that used to live under the old path are now
        // stale (pointing at paths that no longer exist) until it re-scans -- explicitly scanning
        // both the old path (so MediaStore notices the file's gone) and the new one (so it
        // re-indexes at the new location) keeps the gallery in sync immediately, rather than
        // waiting on Android's own background scanner to eventually notice.
        val paths = mutableListOf(oldDir.absolutePath, newDir.absolutePath)
        fun collect(dir: File) {
            dir.listFiles()?.forEach { child ->
                val oldChildPath = oldDir.absolutePath + child.absolutePath.removePrefix(newDir.absolutePath)
                paths += oldChildPath
                paths += child.absolutePath
                if (child.isDirectory) collect(child)
            }
        }
        collect(newDir)

        // Same unbounded-wait risk as rescanUnindexedMedia's own scan above -- bounded for the
        // same reason (see its doc comment).
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                var remaining = paths.size
                MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
                    remaining--
                    if (remaining <= 0 && cont.isActive) cont.resume(Unit)
                }
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
        dateTakenOverrides: Map<Long, Long> = emptyMap(),
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
                // A previously-persisted, confirmed-correct EXIF/filename date (see
                // refineDateTakenFromExif and DateTakenEntity) wins immediately, with no file open
                // needed here -- that's what lets a folder already show up correctly sorted the
                // moment it's opened, on every launch after the first time a photo's real date was
                // resolved, not just eventually once that launch's own background pass catches up.
                // Failing that, this is a fast, first-pass guess only -- MediaStore's own
                // DATE_TAKEN if it has one, else DATE_MODIFIED. This is deliberately NOT reading
                // real EXIF here: this whole query (and therefore this loop) is on the app's
                // blocking "first paint" path, and opening every single not-yet-resolved photo's
                // file to parse its EXIF header here made a large library's first scan sit on
                // "Scanning your photos..." for a very long time. The real per-file EXIF read for
                // anything not already covered by an override happens afterward, in the
                // background, via refineDateTakenFromExif -- see its own doc comment.
                val dateModifiedSec = cursor.getLong(dateCol)
                val dateTakenMs = if (dateTakenCol >= 0) cursor.getLong(dateTakenCol) else 0L
                val dateTakenSec = dateTakenOverrides[id]
                    ?: (if (dateTakenMs > 0) dateTakenMs / 1000 else dateModifiedSec)
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

    /**
     * Re-derives [MediaItem.dateTakenSec] for every photo in [root] not already covered by a
     * persisted [DateTakenEntity] override, from its own EXIF DateTimeOriginal tag read directly
     * from the file, replacing the fast scan's MediaStore-DATE_TAKEN-or-modified-time guess.
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
     * This is the slow half of a refresh -- an actual file open + EXIF header parse per not-yet-
     * resolved photo, versus [scanFolderTree]'s single indexed MediaStore query -- so callers must
     * run it in the BACKGROUND, after already showing [scanFolderTree]'s fast result, exactly like
     * [rescanUnindexedMedia]. Every successfully-resolved date is persisted to [dateTakenDao] (one
     * batched write at the end, not per photo) and skipped here on every later call, in this
     * process or a future one -- [scanFolderTree] applies the same persisted values immediately on
     * its own next run, which is what lets a folder already be correctly sorted the moment it's
     * opened rather than only once this whole-library pass has caught up with it again.
     */
    suspend fun refineDateTakenFromExif(root: FolderNode): FolderNode = withContext(Dispatchers.IO) {
        val alreadyResolved = dateTakenDao.getAll().mapTo(mutableSetOf()) { it.mediaId }
        val toPersist = mutableListOf<com.elghayesh.gallerybackup.data.db.DateTakenEntity>()
        val refined = root.allItemsRecursive().map { item ->
            if (item.isVideo || item.id in alreadyResolved) {
                item
            } else {
                val exifSec = readExifDateTakenSec(item.uri, item.displayName)
                if (exifSec != null) {
                    toPersist += com.elghayesh.gallerybackup.data.db.DateTakenEntity(item.id, exifSec)
                    item.copy(dateTakenSec = exifSec)
                } else {
                    item
                }
            }
        }
        if (toPersist.isNotEmpty()) dateTakenDao.upsertAll(toPersist)
        FolderNode.buildTree(refined)
    }

    /**
     * Reads a capture date straight from EXIF, trying DateTimeOriginal first (the tag meant
     * specifically for "when the shutter opened"), then DateTimeDigitized, then plain DateTime.
     * Third-party batch EXIF-fixing tools (like the one this app's own user described using to
     * correct a whole folder's capture dates) don't all write DateTimeOriginal specifically --
     * some only touch DateTime or DateTimeDigitized, leaving DateTimeOriginal absent or stale.
     *
     * Deliberately does NOT use ExifInterface's own typed getDateTimeOriginal()/getDateTime()
     * (which return a parsed Long directly) -- confirmed via a raw-tag debug dump against a real
     * failing photo that those getters can return null even though the underlying tag is present
     * and perfectly well-formed ("2016:12:30 00:00:00", standard EXIF format). The same photo had
     * an unusually long SubSecTimeOriginal value (6 digits: "564295", vs. the 2-3 digits most
     * cameras write), which is the most likely trigger for whatever the typed getters' internal
     * subsecond handling doesn't like. Reading the raw attribute string with getAttribute() and
     * parsing just the primary "yyyy:MM:dd HH:mm:ss" portion ourselves sidesteps that entirely --
     * capture time to the nearest second is enough for sorting/display, so subsecond precision
     * isn't worth the fragility of depending on it.
     *
     * Falls back to [parseDateFromFilename] when none of those tags produce anything -- some of
     * those same batch tools instead (or additionally) rename the file itself to embed the
     * corrected date, e.g. "2016-12-30.jpg", without ever touching EXIF. A photo whose camera app
     * never wrote a DateTimeOriginal/Digitized/DateTime tag in the first place (common on cheaper
     * or older devices -- Make/Model in IFD0 can be present while the separate Exif SubIFD that
     * holds the date tags is simply absent) would otherwise keep falling through to file-modified
     * time even after a rename tool had already given it the real date, right there in its name.
     */
    private fun readExifDateTakenSec(uri: android.net.Uri, displayName: String): Long? {
        // Catches Throwable, not just Exception: this runs once per not-yet-resolved photo inside
        // a map() over the whole library in refineDateTakenFromExif, with no per-item isolation
        // from its caller -- one photo whose EXIF trips something other than a plain Exception (a
        // corrupt embedded thumbnail causing an OutOfMemoryError, say) would otherwise abort date
        // refinement for every other photo in the library too, not just this one.
        val fromExif = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = androidx.exifinterface.media.ExifInterface(stream)
                val raw = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_DIGITIZED)
                    ?: exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
                raw?.let { parseExifDateTimeString(it) }
            }
        } catch (e: Throwable) {
            null
        }
        return fromExif ?: parseDateFromFilename(displayName)
    }

    /** Standard EXIF date/time tag pattern: "yyyy:MM:dd HH:mm:ss". Deliberately ignores any
     * trailing subsecond/offset tags -- see [readExifDateTakenSec]'s doc comment for why those
     * aren't worth depending on here. */
    private val exifDateTimePattern = Regex("""(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})""")

    private fun parseExifDateTimeString(value: String): Long? {
        val match = exifDateTimePattern.find(value.trim()) ?: return null
        return filenameMatchToEpochSec(match, hasTime = true)
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
