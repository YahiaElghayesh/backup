package com.elghayesh.gallerybackup.data.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * True on API < 30 (scoped storage's directory-listing restriction didn't exist yet) or once the
 * user has granted "All files access" (MANAGE_EXTERNAL_STORAGE) -- needed to browse the real
 * device folder structure beyond what MediaStore has indexed, e.g. in the backup and
 * include/exclude folder pickers. The main gallery never needs this; it stays MediaStore-only.
 */
fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

/** The system settings screen where the user grants (or revokes) "All files access" for this app. */
fun allFilesAccessSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
