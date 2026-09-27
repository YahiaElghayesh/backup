package com.elghayesh.gallerybackup.ui.edit

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs a video trim/remove-selection/speed-change/mute or merge export as a foreground-service-
 * backed WorkManager job -- the same "protect the whole process while this runs" trick
 * [com.elghayesh.gallerybackup.sync.SyncWorker] already uses for backup uploads -- instead of a
 * plain coroutine tied to the editor screen's own composition. A video export can take a while,
 * and without this, backgrounding the app (or navigating away) mid-export left it exposed to the
 * OS reclaiming the process before it finished. [VideoTrimScreen] and [VideoMergeScreen] enqueue
 * this and observe its [androidx.work.WorkInfo] to keep showing live progress; the export itself
 * no longer depends on that observation still being alive to actually finish.
 *
 * Only encodes and inserts the new file -- it deliberately does NOT trash/replace the original.
 * Doing that needs a live Activity (Android 11+'s trash/delete confirmation is a system dialog
 * launched via a [android.app.PendingIntent], which a headless worker has no Activity to launch
 * from), so that step stays with the editor screen, run right when the export finishes if the
 * screen is still around to see it.
 */
class VideoExportWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // See SyncWorker's own doc comment on the identical call -- a refused foreground-service
        // start (e.g. Android 12+ background-start restrictions) must not crash the whole app
        // process; the export below still proceeds, just without the progress notification.
        try {
            setForeground(foregroundInfo())
        } catch (e: Exception) {
            // No progress notification this time.
        }

        return try {
            val outputPath = File(applicationContext.cacheDir, "export_$id.mp4").absolutePath
            val onProgress: (Int) -> Unit = { progress ->
                setProgressAsync(Data.Builder().putInt(KEY_PROGRESS, progress).build())
            }
            val displayName = inputData.getString(KEY_ORIGINAL_DISPLAY_NAME) ?: "video"
            val folderPath = inputData.getString(KEY_ORIGINAL_FOLDER_PATH) ?: ""
            val dateTakenSec = inputData.getLong(KEY_ORIGINAL_DATE_TAKEN_SEC, 0)
            val dateModifiedSec = inputData.getLong(KEY_ORIGINAL_DATE_MODIFIED_SEC, 0)

            val success = when (inputData.getString(KEY_MODE)) {
                MODE_TRIM -> {
                    val sourceUriString = inputData.getString(KEY_SOURCE_URI) ?: return Result.failure()
                    // Transformer binds itself to whichever thread's Looper it's built on (falling
                    // back to the main thread's if the calling thread has none), and every later
                    // call into it (start/cancel/getProgress, all inside transformVideo) must then
                    // come from that SAME thread -- CoroutineWorker.doWork() otherwise runs on a
                    // plain background dispatcher thread with no Looper of its own at all, which
                    // Transformer would reject.
                    val exported = withContext(Dispatchers.Main) {
                        transformVideo(
                            context = applicationContext,
                            sourceUri = Uri.parse(sourceUriString),
                            startMs = inputData.getLong(KEY_START_MS, 0),
                            endMs = inputData.getLong(KEY_END_MS, 0),
                            totalDurationMs = inputData.getLong(KEY_TOTAL_DURATION_MS, 0),
                            removeSelection = inputData.getBoolean(KEY_REMOVE_SELECTION, false),
                            speed = inputData.getFloat(KEY_SPEED, 1f),
                            muteAudio = inputData.getBoolean(KEY_MUTE_AUDIO, false),
                            outputPath = outputPath,
                            onProgress = onProgress,
                        )
                    }
                    if (exported) {
                        saveTrimmedVideo(
                            context = applicationContext,
                            outputPath = outputPath,
                            displayName = displayName,
                            folderPath = folderPath,
                            dateTakenSec = dateTakenSec,
                            dateModifiedSec = dateModifiedSec,
                            replace = inputData.getBoolean(KEY_REPLACE, false),
                        )
                    }
                    exported
                }
                MODE_MERGE -> {
                    val uris = (inputData.getStringArray(KEY_MERGE_URIS) ?: emptyArray()).map { Uri.parse(it) }
                    // See the MODE_TRIM branch above for why this needs Dispatchers.Main.
                    val exported = withContext(Dispatchers.Main) {
                        mergeVideos(applicationContext, uris, outputPath, onProgress)
                    }
                    if (exported) {
                        saveMergedVideo(
                            context = applicationContext,
                            outputPath = outputPath,
                            displayName = displayName,
                            folderPath = folderPath,
                            dateTakenSec = dateTakenSec,
                            dateModifiedSec = dateModifiedSec,
                        )
                    }
                    exported
                }
                else -> false
            }

            if (success) Result.success() else Result.failure()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("MediaHub")
            .setContentText("Saving your video...")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "video_export"
        const val KEY_PROGRESS = "progress"
        private const val NOTIFICATION_ID = 43

        private const val MODE_TRIM = "trim"
        private const val MODE_MERGE = "merge"
        private const val KEY_MODE = "mode"
        private const val KEY_SOURCE_URI = "source_uri"
        private const val KEY_START_MS = "start_ms"
        private const val KEY_END_MS = "end_ms"
        private const val KEY_TOTAL_DURATION_MS = "total_duration_ms"
        private const val KEY_REMOVE_SELECTION = "remove_selection"
        private const val KEY_SPEED = "speed"
        private const val KEY_MUTE_AUDIO = "mute_audio"
        private const val KEY_REPLACE = "replace"
        private const val KEY_MERGE_URIS = "merge_uris"
        private const val KEY_ORIGINAL_DISPLAY_NAME = "original_display_name"
        private const val KEY_ORIGINAL_FOLDER_PATH = "original_folder_path"
        private const val KEY_ORIGINAL_DATE_TAKEN_SEC = "original_date_taken_sec"
        private const val KEY_ORIGINAL_DATE_MODIFIED_SEC = "original_date_modified_sec"

        fun enqueueTrim(
            context: Context,
            sourceUri: Uri,
            startMs: Long,
            endMs: Long,
            totalDurationMs: Long,
            removeSelection: Boolean,
            speed: Float,
            muteAudio: Boolean,
            replace: Boolean,
            originalDisplayName: String,
            originalFolderPath: String,
            originalDateTakenSec: Long,
            originalDateModifiedSec: Long,
        ): UUID {
            val data = Data.Builder()
                .putString(KEY_MODE, MODE_TRIM)
                .putString(KEY_SOURCE_URI, sourceUri.toString())
                .putLong(KEY_START_MS, startMs)
                .putLong(KEY_END_MS, endMs)
                .putLong(KEY_TOTAL_DURATION_MS, totalDurationMs)
                .putBoolean(KEY_REMOVE_SELECTION, removeSelection)
                .putFloat(KEY_SPEED, speed)
                .putBoolean(KEY_MUTE_AUDIO, muteAudio)
                .putBoolean(KEY_REPLACE, replace)
                .putString(KEY_ORIGINAL_DISPLAY_NAME, originalDisplayName)
                .putString(KEY_ORIGINAL_FOLDER_PATH, originalFolderPath)
                .putLong(KEY_ORIGINAL_DATE_TAKEN_SEC, originalDateTakenSec)
                .putLong(KEY_ORIGINAL_DATE_MODIFIED_SEC, originalDateModifiedSec)
                .build()
            val request = OneTimeWorkRequestBuilder<VideoExportWorker>().setInputData(data).build()
            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }

        fun enqueueMerge(
            context: Context,
            uris: List<Uri>,
            firstItemDisplayName: String,
            firstItemFolderPath: String,
            firstItemDateTakenSec: Long,
            firstItemDateModifiedSec: Long,
        ): UUID {
            val data = Data.Builder()
                .putString(KEY_MODE, MODE_MERGE)
                .putStringArray(KEY_MERGE_URIS, uris.map { it.toString() }.toTypedArray())
                .putString(KEY_ORIGINAL_DISPLAY_NAME, firstItemDisplayName)
                .putString(KEY_ORIGINAL_FOLDER_PATH, firstItemFolderPath)
                .putLong(KEY_ORIGINAL_DATE_TAKEN_SEC, firstItemDateTakenSec)
                .putLong(KEY_ORIGINAL_DATE_MODIFIED_SEC, firstItemDateModifiedSec)
                .build()
            val request = OneTimeWorkRequestBuilder<VideoExportWorker>().setInputData(data).build()
            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }
    }
}
