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

/**
 * Tracks the last-uploaded state of one file synced to OneDrive, mirroring [SyncedFileEntity].
 * Kept as a separate table (rather than reusing the Drive one with a prefix) since OneDrive
 * addresses files by path rather than a cached parent-folder id, so there is no OneDrive
 * equivalent of [SyncedFolderEntity] -- folders are created idempotently by path on demand.
 */
@Entity(tableName = "onedrive_synced_files")
data class OneDriveSyncedFileEntity(
    @PrimaryKey val localPath: String,
    val lastModifiedSec: Long,
    val size: Long,
)

@Dao
interface OneDriveSyncedFileDao {
    @Query("SELECT * FROM onedrive_synced_files WHERE localPath = :path")
    suspend fun get(path: String): OneDriveSyncedFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OneDriveSyncedFileEntity)
}

/**
 * A photo's real capture date, once successfully read from its own EXIF (or, failing that, its
 * filename) -- see MediaRepository.refineDateTakenFromExif. Persisting this (instead of just an
 * in-memory, per-process cache) is what lets a folder's sort order be correct the moment it's
 * opened on every later app launch, not just eventually after that launch's own background
 * refinement pass has caught up: MediaRepository.scanFolderTree applies these overrides directly
 * during its own fast, MediaStore-only scan, before the slower per-photo EXIF pass even starts.
 */
@Entity(tableName = "date_taken_overrides")
data class DateTakenEntity(
    @PrimaryKey val mediaId: Long,
    val dateTakenSec: Long,
)

@Dao
interface DateTakenDao {
    @Query("SELECT * FROM date_taken_overrides")
    suspend fun getAll(): List<DateTakenEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DateTakenEntity>)
}

@Database(
    entities = [
        SyncedFolderEntity::class,
        SyncedFileEntity::class,
        TrashedMediaEntity::class,
        OneDriveSyncedFileEntity::class,
        DateTakenEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class BackupDatabase : RoomDatabase() {
    abstract fun syncedFolderDao(): SyncedFolderDao
    abstract fun syncedFileDao(): SyncedFileDao
    abstract fun trashedMediaDao(): TrashedMediaDao
    abstract fun oneDriveSyncedFileDao(): OneDriveSyncedFileDao
    abstract fun dateTakenDao(): DateTakenDao

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
