package com.elghayesh.gallerybackup

import android.content.Context
import java.io.File
import java.util.Date

/**
 * Persists the last uncaught crash to a plain file so it survives the process death that always
 * follows one -- there's no connected computer to pull a logcat from here, so this is the only
 * way to see what actually threw. Installed as the very first thing in [GalleryBackupApp.onCreate]
 * so it's in place before anything else has a chance to crash; read back and shown once by
 * [MainActivity] on the next launch.
 */
object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                File(appContext.filesDir, FILE_NAME).writeText(
                    "Time: ${Date()}\nThread: ${thread.name}\n\n${throwable.stackTraceToString()}",
                )
            } catch (e: Exception) {
                // Nothing more we can do here -- fall through to the previous handler regardless.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Reads and deletes the last saved crash, if any. Call at most once per app open. */
    fun consumeLastCrash(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        return text
    }
}
