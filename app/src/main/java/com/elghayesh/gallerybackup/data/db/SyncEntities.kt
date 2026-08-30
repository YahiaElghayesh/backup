package com.elghayesh.gallerybackup.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

/** Maps a local device folder path ("" = root) to the Drive folder id that mirrors it. */
@Entity(tableName = "synced_folders")
data class SyncedFolderEntity(
    @PrimaryKey val localPath: String,
    val driveFolderId: String,
)

/**
 * Tracks the last-uploaded state of one media file, keyed by its full local path
 * ("DCIM/Camera/IMG_0001.jpg"). Used to skip re-uploading files that haven't changed
 * since the last sync.
 */
@Entity(tableName = "synced_files")
data class SyncedFileEntity(
    @PrimaryKey val localPath: String,
    val driveFileId: String,
    val lastModifiedSec: Long,
    val size: Long,
)

@Dao
interface SyncedFolderDao {
    @Query("SELECT * FROM synced_folders WHERE localPath = :path")
    suspend fun get(path: String): SyncedFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncedFolderEntity)
}

@Dao
interface SyncedFileDao {
    @Query("SELECT * FROM synced_files WHERE localPath = :path")
    suspend fun get(path: String): SyncedFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncedFileEntity)
}

/**
 * MediaHub's own recycle bin. Trashing a file is purely bookkeeping here -- the
 * underlying MediaStore file is untouched, just filtered out of the normal gallery and
 * shown in the Trash screen instead. This is what lets soft-delete skip any OS consent
 * dialog entirely: nothing about the real file changes until a permanent delete happens.
 */
@Entity(tableName = "trashed_media")
data class TrashedMediaEntity(
    @PrimaryKey val mediaId: Long,
    val trashedAtEpochSec: Long,
)

@Dao
interface TrashedMediaDao {
    @Query("SELECT * FROM trashed_media")
    fun observeAll(): Flow<List<TrashedMediaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TrashedMediaEntity>)

    @Query("DELETE FROM trashed_media WHERE mediaId IN (:ids)")
    suspend fun removeAll(ids: List<Long>)

    @Query("SELECT mediaId FROM trashed_media WHERE trashedAtEpochSec < :cutoffEpochSec")
    suspend fun getExpiredIds(cutoffEpochSec: Long): List<Long>
}

@Database(
    entities = [SyncedFolderEntity::class, SyncedFileEntity::class, TrashedMediaEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class BackupDatabase : RoomDatabase() {
    abstract fun syncedFolderDao(): SyncedFolderDao
    abstract fun syncedFileDao(): SyncedFileDao
    abstract fun trashedMediaDao(): TrashedMediaDao

    companion object {
        @Volatile private var instance: BackupDatabase? = null

        fun get(context: Context): BackupDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                BackupDatabase::class.java,
                "gallery_backup.db",
            )
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
