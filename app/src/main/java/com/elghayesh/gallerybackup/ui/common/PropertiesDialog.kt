package com.elghayesh.gallerybackup.ui.common

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.elghayesh.gallerybackup.data.media.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PropertiesDialog(items: List<MediaItem>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (items.size == 1) "Properties" else "Properties (${items.size} items)") },
        text = {
            if (items.size == 1) SingleItemProperties(items.first()) else MultiItemProperties(items)
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SingleItemProperties(item: MediaItem) {
    val context = LocalContext.current
    var dimensions by remember(item.id) { mutableStateOf<String?>(null) }
    // Read straight from the file when this dialog opens, rather than trusting item.dateTakenSec --
    // that field is only as fresh as MediaRepository's own background date-refinement pass, which
    // runs once over the *entire* library and only publishes after every photo in it has been
    // read, so a freshly opened Properties dialog for one specific photo could easily be checked
    // before that whole-library pass has reached (or finished with) this file, showing the rough
    // fast-scan guess (MediaStore's date-taken-or-modified-time fallback) long after the real EXIF
    // date was actually readable. A single photo's own EXIF is cheap enough to just read here,
    // on demand, independent of that -- see readDateTakenSec's own doc comment for the parsing.
    var liveDateTakenSec by remember(item.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(item.id) {
        if (!item.isVideo) {
            dimensions = decodeImageDimensions(context, item)
            liveDateTakenSec = readDateTakenSec(context, item)
        }
    }
    val dateTakenSec = if (item.isVideo) item.dateTakenSec else (liveDateTakenSec ?: item.dateTakenSec)
    Column {
        PropertyRow("Name", item.displayName)
        PropertyRow("Folder", item.folderPath.ifEmpty { "(root)" })
        PropertyRow("Type", item.mimeType)
        PropertyRow("Size", formatBytes(item.size))
        PropertyRow("Modified", DateFormat.getDateTimeInstance().format(Date(item.dateModifiedSec * 1000)))
        PropertyRow("Date taken", DateFormat.getDateTimeInstance().format(Date(dateTakenSec * 1000)))
        if (item.isVideo) {
            PropertyRow("Duration", formatDuration(item.durationMs))
        } else {
            dimensions?.let { PropertyRow("Dimensions", it) }
        }
    }
}

@Composable
private fun MultiItemProperties(items: List<MediaItem>) {
    val totalSize = items.sumOf { it.size }
    val photoCount = items.count { !it.isVideo }
    val videoCount = items.count { it.isVideo }
    val earliest = items.minOf { it.dateModifiedSec }
    val latest = items.maxOf { it.dateModifiedSec }
    Column {
        PropertyRow("Items", "${items.size} ($photoCount photos, $videoCount videos)")
        PropertyRow("Total size", formatBytes(totalSize))
        PropertyRow(
            "Date range",
            "${DateFormat.getDateInstance().format(Date(earliest * 1000))} " +
                "- ${DateFormat.getDateInstance().format(Date(latest * 1000))}",
        )
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Text("$label: $value")
}

private val exifDateTimePattern = Regex("""(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})""")
private val filenameDateTimePatterns = listOf(
    Regex("""(\d{4})[-_](\d{2})[-_](\d{2})[ _T](\d{2})[-:]?(\d{2})[-:]?(\d{2})"""),
    Regex("""(\d{4})(\d{2})(\d{2})[_-](\d{2})(\d{2})(\d{2})"""),
)
private val filenameDateOnlyPattern = Regex("""(\d{4})-(\d{2})-(\d{2})""")

/**
 * Reads [item]'s own capture date on demand, straight from its current file bytes -- the same
 * EXIF-then-filename logic as MediaRepository.readExifDateTakenSec, reading the raw EXIF tag
 * string ourselves (not ExifInterface's typed getDateTimeOriginal()/etc., which have been seen to
 * return null even for a well-formed, present tag -- e.g. when a long SubSecTimeOriginal value
 * trips up their internal parsing) and falling back to a date embedded in the filename
 * (e.g. "2016-12-30.jpg") when EXIF has nothing usable at all. Returns null (letting the caller
 * fall back to [MediaItem.dateTakenSec]) only if neither source has anything to offer.
 */
private suspend fun readDateTakenSec(context: Context, item: MediaItem): Long? =
    withContext(Dispatchers.IO) {
        val fromExif = try {
            context.contentResolver.openInputStream(item.uri)?.use { stream ->
                val exif = androidx.exifinterface.media.ExifInterface(stream)
                val raw = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_DIGITIZED)
                    ?: exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
                raw?.let { value ->
                    exifDateTimePattern.find(value.trim())?.let { dateMatchToEpochSec(it, hasTime = true) }
                }
            }
        } catch (e: Throwable) {
            null
        }
        fromExif ?: parseDateFromFilename(item.displayName)
    }

private fun parseDateFromFilename(displayName: String): Long? {
    val base = displayName.substringBeforeLast('.')
    for (pattern in filenameDateTimePatterns) {
        pattern.find(base)?.let { match -> dateMatchToEpochSec(match, hasTime = true) }?.let { return it }
    }
    return filenameDateOnlyPattern.find(base)?.let { match -> dateMatchToEpochSec(match, hasTime = false) }
}

private fun dateMatchToEpochSec(match: MatchResult, hasTime: Boolean): Long? {
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

private suspend fun decodeImageDimensions(context: Context, item: MediaItem): String? =
    withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(item.uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            if (options.outWidth > 0 && options.outHeight > 0) {
                "${options.outWidth} x ${options.outHeight}"
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private fun formatDuration(durationMs: Long): String {
    val totalSec = durationMs / 1000
    return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
}
