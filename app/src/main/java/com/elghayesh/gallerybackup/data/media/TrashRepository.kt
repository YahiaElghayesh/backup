package com.elghayesh.gallerybackup.data.media

import android.content.Context
import com.elghayesh.gallerybackup.data.db.BackupDatabase
import com.elghayesh.gallerybackup.data.db.TrashedMediaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * MediaHub's own recycle bin bookkeeping: which items are trashed and when, so the UI can show
 * "N days left" against a retention period the user configures (unlike Android's own OS-level
 * trash, which has a fixed ~30 day window no app can control). On Android 11+, this is layered
 * on top of MediaStore's real trash (see [TrashManager] and [GalleryViewModel.deleteMediaItems])
 * rather than replacing it -- the file itself is genuinely hidden from every other app the
 * moment it lands here, not just from MediaHub's own UI.
 */
class TrashRepository(context: Context) {

    private val dao = BackupDatabase.get(context).trashedMediaDao()

    /** mediaId -> the epoch second it was trashed at, so the UI can show "N days left". */
    val trashedEntries: Flow<Map<Long, Long>> =
        dao.observeAll().map { list -> list.associate { it.mediaId to it.trashedAtEpochSec } }

    val trashedIds: Flow<Set<Long>> = trashedEntries.map { it.keys }

    suspend fun trash(ids: List<Long>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis() / 1000
        dao.upsertAll(ids.map { TrashedMediaEntity(it, now) })
    }

    suspend fun restore(ids: List<Long>) {
        if (ids.isEmpty()) return
        dao.removeAll(ids)
    }

    /** Called once an item is permanently deleted, to drop its now-meaningless trash bookkeeping. */
    suspend fun forget(ids: List<Long>) {
        if (ids.isEmpty()) return
        dao.removeAll(ids)
    }

    suspend fun expiredIds(retentionDays: Int): List<Long> {
        val cutoff = System.currentTimeMillis() / 1000 - TimeUnit.DAYS.toSeconds(retentionDays.toLong())
        return dao.getExpiredIds(cutoff)
    }
}
