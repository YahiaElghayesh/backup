package com.elghayesh.gallerybackup.ui.common

import android.content.Context
import android.content.Intent
import com.elghayesh.gallerybackup.data.media.MediaItem

/**
 * Routes to the system's own "Set as" chooser (wallpaper apps, the stock Wallpaper picker with its
 * home/lock-screen/both choice and cropper) via ACTION_ATTACH_DATA -- the same intent gallery apps
 * have used for years to offer "Set as wallpaper" without building a custom cropping UI themselves.
 */
fun setAsWallpaper(context: Context, item: MediaItem) {
    val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
        setDataAndType(item.uri, "image/*")
        putExtra("mimeType", "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Set as"))
}
