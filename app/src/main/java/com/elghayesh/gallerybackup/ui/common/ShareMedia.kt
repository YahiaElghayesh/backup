package com.elghayesh.gallerybackup.ui.common

import android.content.Context
import android.content.Intent
import com.elghayesh.gallerybackup.data.media.MediaItem

fun shareMedia(context: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val mimeType = when {
        items.all { it.isVideo } -> "video/*"
        items.none { it.isVideo } -> "image/*"
        else -> "*/*"
    }
    val intent = if (items.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, items.first().uri)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}
