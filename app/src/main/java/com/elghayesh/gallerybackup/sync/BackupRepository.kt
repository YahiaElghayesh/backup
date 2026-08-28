package com.elghayesh.gallerybackup.sync

import android.content.Context
import com.elghayesh.gallerybackup.data.db.BackupDatabase
import com.elghayesh.gallerybackup.data.db.SyncedFileEntity
import com.elghayesh.gallerybackup.data.db.SyncedFolderEntity
import com.elghayesh.gallerybackup.data.drive.DriveApiClient
import com.elghayesh.gallerybackup.data.drive.DriveAuthManager
import com.elghayesh.gallerybackup.data.drive.DriveAuthResult
import com.elghayesh.gallerybackup.data.media.FolderNode
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.data.media.MediaRepository
import com.elghayesh.gallerybackup.data.media.findNode
import com.elghayesh.gallerybackup.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Mirrors the user's selected on-device folders into a single "GalleryBackup" folder tree
 * in their Drive, preserving the same nested structure -- the opposite of Google Photos'
 * flat-library backup. Already-uploaded, unchanged files are skipped via [BackupDatabase].
 */
class BackupRepository(private val context: Context) {

    private val mediaRepository = MediaRepository(context)
    private val authManager = DriveAuthManager(context)
    private val driveApi = DriveApiClient()
    private val db = BackupDatabase.get(context)
    private val settings = SettingsRepository(context)

    suspend fun sync(onProgress: (String) -> Unit = {}): SyncOutcome {
        val authResult = authManager.authorize()
        val accessToken = (authResult as? DriveAuthResult.Granted)?.accessToken
            ?: return SyncOutcome.NotConnected

        val selectedFolders = settings.selectedFolders.first()
        if (selectedFolders.isEmpty()) return SyncOutcome.NothingSelected

        val tree = mediaRepository.scanFolderTree()

        var uploaded = 0
        var failed = 0
        for (topPath in selectedFolders) {
            val node = tree.findNode(topPath) ?: continue
            onProgress(node.name)
            val (u, f) = syncFolder(accessToken, node, onProgress)
            uploaded += u
            failed += f
        }
        return SyncOutcome.Completed(uploaded = uploaded, failed = failed)
    }

    private suspend fun syncFolder(
        accessToken: String,
        node: FolderNode,
        onProgress: (String) -> Unit,
    ): Pair<Int, Int> {
        val driveFolderId = ensureDriveFolderId(accessToken, node.path)
        var uploaded = 0
        var failed = 0

        for (item in node.items) {
            try {
                if (uploadIfNeeded(accessToken, driveFolderId, item)) {
                    uploaded++
                    onProgress(item.displayName)
                }
            } catch (e: Exception) {
                failed++
            }
        }
        for (child in node.children.values) {
            val (u, f) = syncFolder(accessToken, child, onProgress)
            uploaded += u
            failed += f
        }
        return uploaded to failed
    }

    /** Finds (or creates) the Drive folder id mirroring [localPath], caching the mapping locally. */
    private suspend fun ensureDriveFolderId(accessToken: String, localPath: String): String {
        db.syncedFolderDao().get(localPath)?.let { return it.driveFolderId }

        val parentDriveId: String
        val name: String
        if (localPath.isEmpty()) {
            parentDriveId = DRIVE_ROOT_ALIAS
            name = ROOT_FOLDER_NAME
        } else {
            val lastSlash = localPath.lastIndexOf('/')
            val parentPath = if (lastSlash < 0) "" else localPath.substring(0, lastSlash)
            name = if (lastSlash < 0) localPath else localPath.substring(lastSlash + 1)
            parentDriveId = ensureDriveFolderId(accessToken, parentPath)
        }

        val folder = driveApi.ensureFolder(accessToken, parentDriveId, name)
        db.syncedFolderDao().upsert(SyncedFolderEntity(localPath, folder.id))
        return folder.id
    }

    /** Returns true if a file was actually uploaded (false if it was already up to date). */
    private suspend fun uploadIfNeeded(accessToken: String, driveFolderId: String, item: MediaItem): Boolean {
        val key = itemKey(item)
        val existing = db.syncedFileDao().get(key)
        if (existing != null && existing.lastModifiedSec == item.dateModifiedSec && existing.size == item.size) {
            return false
        }

        val driveFileId = driveApi.uploadFile(
            accessToken = accessToken,
            parentId = driveFolderId,
            contentResolver = context.contentResolver,
            sourceUri = item.uri,
            totalSize = item.size,
            name = item.displayName,
            mimeType = item.mimeType,
            existingFileId = existing?.driveFileId,
        )
        db.syncedFileDao().upsert(
            SyncedFileEntity(
                localPath = key,
                driveFileId = driveFileId,
                lastModifiedSec = item.dateModifiedSec,
                size = item.size,
            ),
        )
        return true
    }

    private fun itemKey(item: MediaItem): String =
        if (item.folderPath.isEmpty()) item.displayName else "${item.folderPath}/${item.displayName}"

    companion object {
        private const val ROOT_FOLDER_NAME = "GalleryBackup"
        private const val DRIVE_ROOT_ALIAS = "root"
    }
}

sealed class SyncOutcome {
    data object NotConnected : SyncOutcome()
    data object NothingSelected : SyncOutcome()
    data class Completed(val uploaded: Int, val failed: Int) : SyncOutcome()
}
