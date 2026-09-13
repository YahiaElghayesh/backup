package com.elghayesh.gallerybackup.sync

import android.content.Context
import com.elghayesh.gallerybackup.data.db.BackupDatabase
import com.elghayesh.gallerybackup.data.db.OneDriveSyncedFileEntity
import com.elghayesh.gallerybackup.data.db.SyncedFileEntity
import com.elghayesh.gallerybackup.data.db.SyncedFolderEntity
import com.elghayesh.gallerybackup.data.drive.DriveApiClient
import com.elghayesh.gallerybackup.data.drive.DriveAuthManager
import com.elghayesh.gallerybackup.data.drive.DriveAuthResult
import com.elghayesh.gallerybackup.data.media.FolderNode
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.data.media.MediaRepository
import com.elghayesh.gallerybackup.data.media.allFolderPathsRecursive
import com.elghayesh.gallerybackup.data.onedrive.OneDriveApiClient
import com.elghayesh.gallerybackup.data.onedrive.OneDriveAuthManager
import com.elghayesh.gallerybackup.data.onedrive.OneDriveSettingsRepository
import com.elghayesh.gallerybackup.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Mirrors the user's selected on-device folders into a "GalleryBackup" folder tree on
 * whichever cloud target(s) are connected and enabled -- Google Drive, OneDrive, or both --
 * preserving the same nested structure, the opposite of Google Photos' flat-library backup.
 * Both targets share the same folder selection; each tracks its own already-uploaded state
 * independently ([BackupDatabase]'s Drive tables vs its OneDrive table) so enabling a second
 * target later re-uploads everything to it without disturbing the first.
 */
class BackupRepository(private val context: Context) {

    private val mediaRepository = MediaRepository(context)
    private val driveAuthManager = DriveAuthManager(context)
    private val driveApi = DriveApiClient()
    private val oneDriveAuthManager = OneDriveAuthManager(context)
    private val oneDriveApi = OneDriveApiClient()
    private val db = BackupDatabase.get(context)
    private val settings = SettingsRepository(context)
    private val oneDriveSettings = OneDriveSettingsRepository(context)

    /**
     * Selecting a folder backs up its own items plus everything in every folder beneath it --
     * picking "DCIM" pulls in "DCIM/Screenshots" too, without needing it individually selected.
     * A subfolder can still be selected on its own without its parent being selected, for a
     * narrower backup than the whole parent.
     */
    suspend fun sync(onProgress: suspend (String) -> Unit = {}): SyncOutcome {
        val driveToken = (driveAuthManager.authorize() as? DriveAuthResult.Granted)?.accessToken
        val oneDriveToken = if (oneDriveSettings.enabled.first()) oneDriveAuthManager.getValidAccessToken() else null
        if (driveToken == null && oneDriveToken == null) return SyncOutcome.NotConnected

        val selectedFolders = settings.selectedFolders.first()
        val tree = mediaRepository.scanFolderTree()
        // If the user has turned on "auto-include future folders", any folder that gained media
        // AFTER that toggle was last switched on (i.e. isn't in the baseline snapshot taken at that
        // moment -- see SettingsRepository.autoIncludeFutureFolders) is backed up automatically,
        // exactly as if it had been selected by hand. A folder that already had media at that
        // moment still needs to be in [selectedFolders] same as always.
        val effectiveSelectedFolders = if (settings.autoIncludeFutureFolders.first()) {
            val baseline = settings.knownFoldersBaseline.first()
            selectedFolders + (tree.allFolderPathsRecursive() - baseline)
        } else {
            selectedFolders
        }
        if (effectiveSelectedFolders.isEmpty()) return SyncOutcome.NothingSelected

        var uploaded = 0
        var failed = 0
        if (driveToken != null) {
            val (u, f) = syncTreeToDrive(driveToken, tree, effectiveSelectedFolders, onProgress)
            uploaded += u
            failed += f
        }
        if (oneDriveToken != null) {
            val (u, f) = syncTreeToOneDrive(oneDriveToken, tree, effectiveSelectedFolders, onProgress)
            uploaded += u
            failed += f
        }
        return SyncOutcome.Completed(uploaded = uploaded, failed = failed)
    }

    private suspend fun syncTreeToDrive(
        accessToken: String,
        node: FolderNode,
        selectedFolders: Set<String>,
        onProgress: suspend (String) -> Unit,
        inheritedSelected: Boolean = false,
    ): Pair<Int, Int> {
        var uploaded = 0
        var failed = 0

        val included = inheritedSelected || (node.path.isNotEmpty() && node.path in selectedFolders)
        if (included) {
            val driveFolderId = ensureDriveFolderId(accessToken, node.path)
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
        }
        for (child in node.children.values) {
            val (u, f) = syncTreeToDrive(accessToken, child, selectedFolders, onProgress, inheritedSelected = included)
            uploaded += u
            failed += f
        }
        return uploaded to failed
    }

    private suspend fun syncTreeToOneDrive(
        accessToken: String,
        node: FolderNode,
        selectedFolders: Set<String>,
        onProgress: suspend (String) -> Unit,
        inheritedSelected: Boolean = false,
    ): Pair<Int, Int> {
        var uploaded = 0
        var failed = 0

        val included = inheritedSelected || (node.path.isNotEmpty() && node.path in selectedFolders)
        if (included) {
            val remoteFolderPath = "$ROOT_FOLDER_NAME/${node.path}"
            try {
                oneDriveApi.ensureFolderPath(accessToken, remoteFolderPath)
                for (item in node.items) {
                    try {
                        if (uploadToOneDriveIfNeeded(accessToken, remoteFolderPath, item)) {
                            uploaded++
                            onProgress(item.displayName)
                        }
                    } catch (e: Exception) {
                        failed++
                    }
                }
            } catch (e: Exception) {
                failed += node.items.size
            }
        }
        for (child in node.children.values) {
            val (u, f) = syncTreeToOneDrive(accessToken, child, selectedFolders, onProgress, inheritedSelected = included)
            uploaded += u
            failed += f
        }
        return uploaded to failed
    }

    /** Returns true if a file was actually uploaded (false if it was already up to date). */
    private suspend fun uploadToOneDriveIfNeeded(accessToken: String, remoteFolderPath: String, item: MediaItem): Boolean {
        val key = itemKey(item)
        val existing = db.oneDriveSyncedFileDao().get(key)
        if (existing != null && existing.lastModifiedSec == item.dateModifiedSec && existing.size == item.size) {
            return false
        }

        oneDriveApi.uploadFile(
            accessToken = accessToken,
            folderPath = remoteFolderPath,
            contentResolver = context.contentResolver,
            sourceUri = item.uri,
            totalSize = item.size,
            name = item.displayName,
        )
        db.oneDriveSyncedFileDao().upsert(
            OneDriveSyncedFileEntity(
                localPath = key,
                lastModifiedSec = item.dateModifiedSec,
                size = item.size,
            ),
        )
        return true
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
