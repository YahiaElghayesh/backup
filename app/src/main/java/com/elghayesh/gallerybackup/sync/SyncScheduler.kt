package com.elghayesh.gallerybackup.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

/**
 * Two complementary paths keep backups current:
 *  - [schedulePeriodicSync]: WorkManager's guaranteed catch-up job, every 15 minutes
 *    (Android's minimum for periodic work), so nothing is missed even if the app was
 *    never opened after a change or the process was killed.
 *  - [runOneOffSync] / [runDebouncedSyncOnChange]: an immediate/near-immediate pass,
 *    triggered by a manual "Back up now" tap or by [MediaChangeObserver] noticing new
 *    media while the app process is alive.
 */
object SyncScheduler {

    private const val PERIODIC_WORK_NAME = "gallery_backup_periodic_sync"
    private const val ON_DEMAND_WORK_NAME = "gallery_backup_on_demand_sync"

    fun schedulePeriodicSync(context: Context, wifiOnly: Boolean) {
        // WorkManager.getInstance() throws if WorkManager failed to initialize (see
        // GalleryBackupApp.initializeWorkManager) -- every caller of this object, including ones
        // on the UI thread (toggling the Wi-Fi-only switch) and unrelated background coroutines
        // (MediaChangeObserver), must never crash just because background sync isn't available.
        runCatching {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints(wifiOnly))
                .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

    /** A user-initiated "Back up now" -- runs as soon as there's any network, ignoring the Wi-Fi-only setting. */
    fun runOneOffSync(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ON_DEMAND_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    /** Called when new/changed media is observed. Short delay lets a burst of changes coalesce into one sync. */
    fun runDebouncedSyncOnChange(context: Context, wifiOnly: Boolean) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setConstraints(networkConstraints(wifiOnly))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ON_DEMAND_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    private fun networkConstraints(wifiOnly: Boolean): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
}
