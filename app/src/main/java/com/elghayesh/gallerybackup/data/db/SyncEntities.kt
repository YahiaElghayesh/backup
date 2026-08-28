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

@Database(entities = [SyncedFolderEntity::class, SyncedFileEntity::class], version = 1, exportSchema = false)
abstract class BackupDatabase : RoomDatabase() {
    abstract fun syncedFolderDao(): SyncedFolderDao
    abstract fun syncedFileDao(): SyncedFileDao

    companion object {
        @Volatile private var instance: BackupDatabase? = null

        fun get(context: Context): BackupDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                BackupDatabase::class.java,
                "gallery_backup.db",
            ).build().also { instance = it }
        }
    }
}
