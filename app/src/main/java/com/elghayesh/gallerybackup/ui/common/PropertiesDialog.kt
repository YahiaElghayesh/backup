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
    LaunchedEffect(item.id) {
        if (!item.isVideo) dimensions = decodeImageDimensions(context, item)
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
