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
    var exifDebug by remember(item.id) { mutableStateOf<ExifDebugInfo?>(null) }
    LaunchedEffect(item.id) {
        if (!item.isVideo) {
            dimensions = decodeImageDimensions(context, item)
            exifDebug = readExifDebugInfo(context, item)
        }
    }
    Column {
        PropertyRow("Name", item.displayName)
        PropertyRow("Folder", item.folderPath.ifEmpty { "(root)" })
        PropertyRow("Type", item.mimeType)
        PropertyRow("Size", formatBytes(item.size))
        PropertyRow("Modified", DateFormat.getDateTimeInstance().format(Date(item.dateModifiedSec * 1000)))
        PropertyRow("Date taken", DateFormat.getDateTimeInstance().format(Date(item.dateTakenSec * 1000)))
        if (item.isVideo) {
            PropertyRow("Duration", formatDuration(item.durationMs))
        } else {
            dimensions?.let { PropertyRow("Dimensions", it) }
        }
        // TEMPORARY diagnostic rows -- see readExifDebugInfo's doc comment. Not meant to stay in
        // the shipped Properties dialog. "Computed date taken" independently re-derives the date
        // right here, live, from a fresh file read -- bypassing MediaRepository/its cache/the
        // refresh() pipeline entirely -- so we can tell apart "the parsing logic is still wrong"
        // from "the parsing is fine but its result never reaches what's displayed above".
        exifDebug?.let { debug ->
            PropertyRow("EXIF debug", debug.raw)
            PropertyRow(
                "Computed date taken",
                debug.computedSec?.let { DateFormat.getDateTimeInstance().format(Date(it * 1000)) }
                    ?: "computed null",
            )
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

private data class ExifDebugInfo(val raw: String, val computedSec: Long?)

private val exifDateTimePattern = Regex("""(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})""")

/** TEMPORARY diagnostic: reads the raw (unparsed) EXIF date-related tag strings directly from
 * the file, bypassing MediaRepository's cache and typed getters entirely, so we can see exactly
 * what -- if anything -- is actually stored in a given photo's EXIF, and whether the app's own
 * date-tag reading is throwing partway through. Also independently re-parses that raw string
 * right here (mirroring MediaRepository.parseExifDateTimeString's logic exactly, but computed
 * fresh in this dialog rather than read back from the repository) -- see the call site's comment
 * for why. */
private suspend fun readExifDebugInfo(context: Context, item: MediaItem): ExifDebugInfo =
    withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(item.uri)?.use { stream ->
                val exif = androidx.exifinterface.media.ExifInterface(stream)
                val original = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                val digitized = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_DIGITIZED)
                val dateTime = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
                val subsec = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)
                val offset = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                val make = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE)
                val raw = "Original=$original Digitized=$digitized DateTime=$dateTime " +
                    "Subsec=$subsec Offset=$offset Make=$make"
                val chosen = original ?: digitized ?: dateTime
                val computedSec = chosen?.let { value ->
                    exifDateTimePattern.find(value.trim())?.let { match ->
                        val g = match.groupValues
                        val cal = java.util.Calendar.getInstance().apply {
                            clear()
                            set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
                        }
                        cal.timeInMillis / 1000
                    }
                }
                ExifDebugInfo(raw, computedSec)
            } ?: ExifDebugInfo("could not open stream", null)
        } catch (e: Throwable) {
            ExifDebugInfo("threw: ${e::class.simpleName}: ${e.message}", null)
        }
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
