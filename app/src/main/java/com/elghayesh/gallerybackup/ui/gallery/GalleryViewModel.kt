package com.elghayesh.gallerybackup.ui.gallery

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elghayesh.gallerybackup.data.media.DeleteResult
import com.elghayesh.gallerybackup.data.media.FolderNode
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.data.media.MediaRepository
import com.elghayesh.gallerybackup.data.media.TrashManager
import com.elghayesh.gallerybackup.data.media.TrashRepository
import com.elghayesh.gallerybackup.data.media.allItemsRecursive
import com.elghayesh.gallerybackup.data.media.copyMediaTo
import com.elghayesh.gallerybackup.data.media.filtered
import com.elghayesh.gallerybackup.data.media.withVirtualFolders
import com.elghayesh.gallerybackup.data.settings.AccentColor
import com.elghayesh.gallerybackup.data.settings.FolderCover
import com.elghayesh.gallerybackup.data.settings.FolderSortOrder
import com.elghayesh.gallerybackup.data.settings.FolderSortOverride
import com.elghayesh.gallerybackup.data.settings.GalleryPreferencesRepository
import com.elghayesh.gallerybackup.data.settings.ThemeMode
import com.elghayesh.gallerybackup.data.settings.ViewType
import com.elghayesh.gallerybackup.sync.MediaChangeSignal
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Scope for a sort choice made from a specific folder: the new global default, just that
 * folder's own listing, or that folder plus every descendant beneath it. */
enum class SortScope { ALL, THIS_FOLDER, THIS_FOLDER_AND_SUBFOLDERS }

private data class RootFilterInputs(
    val raw: FolderNode?,
    val hiddenFolders: Set<String>,
    val hiddenMedia: Set<Long>,
    val showHidden: Boolean,
)

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = MediaRepository(app)
    private val prefs = GalleryPreferencesRepository(app)
    private val trashManager = TrashManager(app)
    private val trashRepository = TrashRepository(app)

    private val deleteConsentChannel = Channel<PendingIntent>(Channel.CONFLATED)
    val deleteConsentRequests: Flow<PendingIntent> = deleteConsentChannel.receiveAsFlow()
    private var pendingDeleteIds: List<Long> = emptyList()
    private var pendingDeleteIsSoft: Boolean = false

    private val restoreConsentChannel = Channel<PendingIntent>(Channel.CONFLATED)
    val restoreConsentRequests: Flow<PendingIntent> = restoreConsentChannel.receiveAsFlow()
    private var pendingRestoreIds: List<Long> = emptyList()

    private val _selectedMediaIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedMediaIds: StateFlow<Set<Long>> = _selectedMediaIds.asStateFlow()
    private val _selectedFolderPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedFolderPaths: StateFlow<Set<String>> = _selectedFolderPaths.asStateFlow()

    /** Session-only (not persisted) delete-confirmation preferences -- reset if the app process dies. */
    private val _dontAskAgainDelete = MutableStateFlow(false)
    val dontAskAgainDelete: StateFlow<Boolean> = _dontAskAgainDelete.asStateFlow()
    private val _skipRecycleBinDefault = MutableStateFlow(false)
    val skipRecycleBinDefault: StateFlow<Boolean> = _skipRecycleBinDefault.asStateFlow()

    private val _rawRoot = MutableStateFlow<FolderNode?>(null)

    /** The full, unfiltered scan -- used by backup folder selection, which should see everything. */
    val root: StateFlow<FolderNode?> = _rawRoot.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Every real folder on the device, MediaStore-indexed or not -- see [MediaRepository.listAllDeviceFolderPaths].
     * Loaded lazily by the folder pickers that need it, not on every gallery refresh. */
    private val _allDeviceFolderPaths = MutableStateFlow<Set<String>>(emptySet())
    val allDeviceFolderPaths: StateFlow<Set<String>> = _allDeviceFolderPaths.asStateFlow()

    fun refreshAllDeviceFolders() {
        viewModelScope.launch {
            _allDeviceFolderPaths.value = repository.listAllDeviceFolderPaths()
        }
    }

    val folderViewType: StateFlow<ViewType> =
        prefs.folderViewType.stateIn(viewModelScope, SharingStarted.Eagerly, ViewType.GRID)
    val mediaViewType: StateFlow<ViewType> =
        prefs.mediaViewType.stateIn(viewModelScope, SharingStarted.Eagerly, ViewType.GRID)
    val folderGridColumns: StateFlow<Int> =
        prefs.folderGridColumns.stateIn(viewModelScope, SharingStarted.Eagerly, 3)
    val mediaGridColumns: StateFlow<Int> =
        prefs.mediaGridColumns.stateIn(viewModelScope, SharingStarted.Eagerly, 3)
    val folderRowSize: StateFlow<Int> =
        prefs.folderRowSize.stateIn(viewModelScope, SharingStarted.Eagerly, 48)
    val mediaRowSize: StateFlow<Int> =
        prefs.mediaRowSize.stateIn(viewModelScope, SharingStarted.Eagerly, 48)
    val themeMode: StateFlow<ThemeMode> =
        prefs.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val accentColor: StateFlow<AccentColor> =
        prefs.accentColor.stateIn(viewModelScope, SharingStarted.Eagerly, AccentColor.BLUE)
    val folderSort: StateFlow<FolderSortOrder> =
        prefs.folderSort.stateIn(viewModelScope, SharingStarted.Eagerly, FolderSortOrder.NAME_ASC)
    val folderSortOverrides: StateFlow<Map<String, FolderSortOverride>> =
        prefs.folderSortOverrides.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val includedFolders: StateFlow<Set<String>> =
        prefs.includedFolders.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    /** Paths of folders created in-app before they have any real media -- see [FolderNode.withVirtualFolders]. */
    val virtualFolders: StateFlow<Set<String>> =
        prefs.virtualFolders.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val hiddenFolders: StateFlow<Set<String>> =
        prefs.hiddenFolders.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val hiddenMediaIds: StateFlow<Set<Long>> =
        prefs.hiddenMediaIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val showHidden: StateFlow<Boolean> =
        prefs.showHidden.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val folderCovers: StateFlow<Map<String, FolderCover>> =
        prefs.folderCovers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val trashRetentionDays: StateFlow<Int> =
        prefs.trashRetentionDays.stateIn(viewModelScope, SharingStarted.Eagerly, 30)
    val trashedEntries: StateFlow<Map<Long, Long>> =
        trashRepository.trashedEntries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val favoriteMediaIds: StateFlow<Set<Long>> =
        prefs.favoriteMediaIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val pinContentToBottom: StateFlow<Boolean> =
        prefs.pinContentToBottom.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val dateDividerFolders: StateFlow<Set<String>> =
        prefs.dateDividerFolders.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** [root] with hidden/trashed content removed, plus any still-empty user-created folders added in. */
    val visibleRoot: StateFlow<FolderNode?> = combine(
        combine(
            _rawRoot,
            prefs.hiddenFolders,
            prefs.hiddenMediaIds,
            prefs.showHidden,
        ) { raw, hiddenFolders, hiddenMedia, showHidden ->
            RootFilterInputs(raw, hiddenFolders, hiddenMedia, showHidden)
        },
        trashRepository.trashedIds,
        prefs.virtualFolders,
    ) { inputs, trashedIds, virtualFolders ->
        inputs.raw
            ?.filtered(inputs.hiddenFolders, inputs.hiddenMedia, inputs.showHidden, trashedIds)
            ?.withVirtualFolders(virtualFolders)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** A MediaStore-trashed-rows-only scan, refreshed whenever [trashedEntries] changes -- see
     * [MediaRepository.scanTrashedItems]. Needed because [_rawRoot]'s regular scan excludes
     * IS_TRASHED rows entirely on Android 11+, no matter the selection, so a trashed item can no
     * longer be found there once MediaStore's own trash actually holds it. */
    private val _trashedScan = MutableStateFlow<List<MediaItem>>(emptyList())

    /** Every trashed item. On Android 11+ these come from [_trashedScan], since MediaStore's real
     * trash removes the item from [_rawRoot]'s normal scan the moment it's trashed. Below Android
     * 11, trashing is pure local bookkeeping with the file left untouched, so it's still resolved
     * from the regular scan there instead. */
    val trashedItems: StateFlow<List<MediaItem>> = combine(_rawRoot, trashedEntries, _trashedScan) { raw, entries, scanned ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scanned.filter { it.id in entries.keys }
        } else {
            raw?.allItemsRecursive()?.filter { it.id in entries.keys } ?: emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        trashedEntries
            .onEach { refreshTrashedScan() }
            .launchIn(viewModelScope)
        // MediaChangeObserver fires once per changed row, which can be many times in a row for a
        // burst (e.g. an app writing several photos back to back) -- debounce so a burst settles
        // into a single rescan instead of one per row. This is the "refresh automatically" half of
        // keeping the gallery in sync with the device; [refresh] itself is the "refresh faster on
        // demand" half, wired to pull-to-refresh and the "Rescan device" menu item.
        MediaChangeSignal.changes
            .debounce(1500)
            .onEach { refresh() }
            .launchIn(viewModelScope)
    }

    fun loadIfNeeded() {
        if (_rawRoot.value != null || _isLoading.value) return
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.rescanUnindexedMedia()
            _rawRoot.value = repository.scanFolderTree()
            _isLoading.value = false
        }
    }

    private fun refreshTrashedScan() {
        viewModelScope.launch { _trashedScan.value = repository.scanTrashedItems() }
    }

    fun setFolderViewType(type: ViewType) = viewModelScope.launch { prefs.setFolderViewType(type) }
    fun setMediaViewType(type: ViewType) = viewModelScope.launch { prefs.setMediaViewType(type) }
    fun setFolderGridColumns(columns: Int) = viewModelScope.launch { prefs.setFolderGridColumns(columns) }
    fun setMediaGridColumns(columns: Int) = viewModelScope.launch { prefs.setMediaGridColumns(columns) }
    fun setFolderRowSize(sizeDp: Int) = viewModelScope.launch { prefs.setFolderRowSize(sizeDp) }
    fun setMediaRowSize(sizeDp: Int) = viewModelScope.launch { prefs.setMediaRowSize(sizeDp) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(mode) }
    fun setAccentColor(color: AccentColor) = viewModelScope.launch { prefs.setAccentColor(color) }
    /** Applies [order] either as the new global default (also clearing any override sitting at
     * [path] so the change is visible immediately where it was made), to just [path]'s own
     * listing, or to [path] and everything beneath it that doesn't have its own override. */
    fun setFolderSort(order: FolderSortOrder, scope: SortScope, path: String) {
        viewModelScope.launch {
            when (scope) {
                SortScope.ALL -> {
                    prefs.setFolderSort(order)
                    prefs.clearFolderSortOverride(path)
                }
                SortScope.THIS_FOLDER -> prefs.setFolderSortOverride(path, order, includeSubfolders = false)
                SortScope.THIS_FOLDER_AND_SUBFOLDERS -> prefs.setFolderSortOverride(path, order, includeSubfolders = true)
            }
        }
    }
    fun setFolderHidden(path: String, hidden: Boolean) =
        viewModelScope.launch { prefs.setFolderHidden(path, hidden) }
    fun setFolderIncluded(path: String, included: Boolean) =
        viewModelScope.launch { prefs.setFolderIncluded(path, included) }
    fun unhideAllFolders() = viewModelScope.launch { prefs.unhideAllFolders() }
    fun setMediaHidden(id: Long, hidden: Boolean) =
        viewModelScope.launch { prefs.setMediaHidden(id, hidden) }
    fun setShowHidden(show: Boolean) = viewModelScope.launch { prefs.setShowHidden(show) }
    fun setTrashRetentionDays(days: Int) = viewModelScope.launch { prefs.setTrashRetentionDays(days) }
    fun setFolderCover(path: String, cover: FolderCover?) = viewModelScope.launch { prefs.setFolderCover(path, cover) }

    fun setMediaFavorite(id: Long, favorite: Boolean) = viewModelScope.launch { prefs.setMediaFavorite(id, favorite) }
    fun setPinContentToBottom(value: Boolean) = viewModelScope.launch { prefs.setPinContentToBottom(value) }
    fun setFolderDateDividers(path: String, enabled: Boolean) =
        viewModelScope.launch { prefs.setFolderDateDividers(path, enabled) }

    /** Favorites every id in [ids] if any of them isn't already a favorite, otherwise un-favorites them all. */
    fun toggleFavorites(ids: List<Long>) {
        if (ids.isEmpty()) return
        val allFavorited = ids.all { it in favoriteMediaIds.value }
        viewModelScope.launch { ids.forEach { prefs.setMediaFavorite(it, !allFavorited) } }
    }

    fun createFolder(parentPath: String, name: String) {
        viewModelScope.launch {
            val newPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
            prefs.addVirtualFolder(newPath)
        }
    }

    fun toggleMediaSelection(id: Long) {
        _selectedMediaIds.value =
            if (id in _selectedMediaIds.value) _selectedMediaIds.value - id else _selectedMediaIds.value + id
    }

    fun setMediaSelected(id: Long, selected: Boolean) {
        _selectedMediaIds.value = if (selected) _selectedMediaIds.value + id else _selectedMediaIds.value - id
    }

    fun toggleFolderSelection(path: String) {
        _selectedFolderPaths.value =
            if (path in _selectedFolderPaths.value) _selectedFolderPaths.value - path else _selectedFolderPaths.value + path
    }

    fun setFolderSelected(path: String, selected: Boolean) {
        _selectedFolderPaths.value = if (selected) _selectedFolderPaths.value + path else _selectedFolderPaths.value - path
    }

    fun clearSelection() {
        _selectedMediaIds.value = emptySet()
        _selectedFolderPaths.value = emptySet()
    }

    fun setDontAskAgainDelete(value: Boolean) {
        _dontAskAgainDelete.value = value
    }

    fun setSkipRecycleBinDefault(value: Boolean) {
        _skipRecycleBinDefault.value = value
    }

    /**
     * Deletes [items]. On Android 11+, soft-deleting now goes through MediaStore's own trash
     * (one system confirmation dialog, same as [skipTrash]'s permanent delete) so the item is
     * actually hidden from every other app immediately -- not just from MediaHub's own UI. It
     * used to be pure local bookkeeping with the underlying file left completely untouched and
     * still fully visible everywhere else (another app's share sheet, a file manager, ...),
     * which defeated the point of a recycle bin. MediaHub's own trash tracking, layered on top
     * regardless, is still what drives the custom retention period and "N days left" -- the OS
     * trash itself has no per-app-configurable retention. Below Android 11 there's no real OS
     * trash concept at all ([TrashManager] would hard-delete immediately), so soft delete stays
     * pure local bookkeeping there; the file remains visible to other apps until permanently
     * deleted, a real platform limitation on those versions, not an oversight.
     */
    fun deleteMediaItems(items: List<MediaItem>, skipTrash: Boolean) {
        viewModelScope.launch {
            if (!skipTrash && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                trashRepository.trash(items.map { it.id })
                clearSelection()
                return@launch
            }
            pendingDeleteIds = items.map { it.id }
            pendingDeleteIsSoft = !skipTrash
            when (val result = trashManager.requestDelete(items.map { it.uri }, skipTrash = skipTrash)) {
                is DeleteResult.ConsentRequired -> deleteConsentChannel.send(result.pendingIntent)
                DeleteResult.Deleted -> onDeleteConfirmed(approved = true)
                is DeleteResult.Error -> Unit
            }
        }
    }

    /** Call after the system dialog launched from [deleteConsentRequests] resolves. [approved]
     * is false if the user cancelled it -- MediaStore never touched the files in that case, so
     * neither should MediaHub's own trash bookkeeping; the pending soft-trash or permanent
     * delete just never happened. */
    fun onDeleteConfirmed(approved: Boolean) {
        viewModelScope.launch {
            if (approved) {
                if (pendingDeleteIsSoft) {
                    trashRepository.trash(pendingDeleteIds)
                } else {
                    trashRepository.forget(pendingDeleteIds)
                }
                refresh()
            }
            pendingDeleteIds = emptyList()
            clearSelection()
        }
    }

    /** Restores [ids] out of the trash. On Android 11+ this un-trashes the underlying MediaStore
     * rows themselves (one system consent dialog, the same API used to trash them) so the items
     * actually come back everywhere, not just in MediaHub's own bookkeeping -- clearing only the
     * local bookkeeping would leave the file genuinely trashed in MediaStore, so it would neither
     * show back up in the gallery nor still show in Trash. Below Android 11 there's no OS trash
     * to reverse, so this stays local bookkeeping there, same as trashing does. */
    fun restoreFromTrash(ids: List<Long>) {
        viewModelScope.launch {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                trashRepository.restore(ids)
                return@launch
            }
            val items = _trashedScan.value.filter { it.id in ids }
            if (items.isEmpty()) {
                trashRepository.restore(ids)
                return@launch
            }
            pendingRestoreIds = items.map { it.id }
            when (val result = trashManager.requestRestore(items.map { it.uri })) {
                is DeleteResult.ConsentRequired -> restoreConsentChannel.send(result.pendingIntent)
                DeleteResult.Deleted -> onRestoreConfirmed(approved = true)
                is DeleteResult.Error -> Unit
            }
        }
    }

    /** Call after the system dialog launched from [restoreConsentRequests] resolves. */
    fun onRestoreConfirmed(approved: Boolean) {
        viewModelScope.launch {
            if (approved) {
                trashRepository.restore(pendingRestoreIds)
                refresh()
                refreshTrashedScan()
            }
            pendingRestoreIds = emptyList()
        }
    }

    /** Checks for trash past [trashRetentionDays] and, if any, starts permanently deleting it. */
    fun purgeExpiredTrash() {
        viewModelScope.launch {
            val expiredIds = trashRepository.expiredIds(trashRetentionDays.value)
            if (expiredIds.isEmpty()) return@launch
            val items = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                _trashedScan.value.filter { it.id in expiredIds }
            } else {
                _rawRoot.value?.allItemsRecursive()?.filter { it.id in expiredIds } ?: emptyList()
            }
            if (items.isNotEmpty()) deleteMediaItems(items, skipTrash = true)
        }
    }

    /** Empties the trash entirely: permanently deletes every currently-trashed item. */
    fun emptyTrash() {
        viewModelScope.launch {
            val items = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                _trashedScan.value
            } else {
                val ids = trashedEntries.value.keys
                _rawRoot.value?.allItemsRecursive()?.filter { it.id in ids } ?: emptyList()
            }
            if (items.isNotEmpty()) deleteMediaItems(items, skipTrash = true)
        }
    }

    /**
     * Trashes [items]' underlying MediaStore rows -- used after a move or rename, which copies
     * to the new location and then needs the original gone. Goes through the same real
     * MediaStore trash (a system consent dialog) as [deleteMediaItems] on Android 11+, so the
     * originals are actually hidden from every other app immediately -- move/rename used to call
     * [TrashRepository.trash] directly here, which is pure local bookkeeping that never touches
     * MediaStore at all, leaving the "moved" original fully visible everywhere else. Below
     * Android 11, where there's no real OS trash to hook into, this stays local bookkeeping.
     */
    private suspend fun trashOriginals(items: List<MediaItem>) {
        if (items.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            trashRepository.trash(items.map { it.id })
            return
        }
        pendingDeleteIds = items.map { it.id }
        pendingDeleteIsSoft = true
        val result = trashManager.requestDelete(items.map { it.uri }, skipTrash = false)
        if (result is DeleteResult.ConsentRequired) {
            deleteConsentChannel.send(result.pendingIntent)
        }
    }

    /** Copies [items] into [destinationFolderPath], leaving the originals in place. */
    fun copyMediaItems(items: List<MediaItem>, destinationFolderPath: String) {
        viewModelScope.launch {
            val context: Context = getApplication()
            for (item in items) copyMediaTo(context, item, destinationFolderPath)
            refresh()
            clearSelection()
        }
    }

    /** Copies [items] into [destinationFolderPath], then trashes the originals. */
    fun moveMediaItems(items: List<MediaItem>, destinationFolderPath: String) {
        viewModelScope.launch {
            val context: Context = getApplication()
            val copiedItems = items.filter { copyMediaTo(context, it, destinationFolderPath) != null }
            trashOriginals(copiedItems)
            refresh()
            clearSelection()
        }
    }

    /** Renames [item] by copying its bytes into a new file with [newDisplayName] and trashing the original. */
    fun renameMediaItem(item: MediaItem, newDisplayName: String) {
        viewModelScope.launch {
            val context: Context = getApplication()
            val ext = item.displayName.substringAfterLast('.', "")
            val finalName = if (ext.isNotEmpty() && !newDisplayName.endsWith(".$ext", ignoreCase = true)) {
                "$newDisplayName.$ext"
            } else {
                newDisplayName
            }
            val renamed = item.copy(displayName = finalName)
            if (copyMediaTo(context, renamed, item.folderPath) != null) {
                trashOriginals(listOf(item))
            }
            refresh()
            clearSelection()
        }
    }

    fun deleteFolder(folder: FolderNode, skipTrash: Boolean) {
        deleteMediaItems(folder.allItemsRecursive(), skipTrash)
        viewModelScope.launch { prefs.setFolderCover(folder.path, null) }
    }

    fun copyFolder(folder: FolderNode, destinationParentPath: String) {
        viewModelScope.launch {
            val context: Context = getApplication()
            val newFolderPath = if (destinationParentPath.isEmpty()) folder.name else "$destinationParentPath/${folder.name}"
            for (item in folder.allItemsRecursive()) {
                copyMediaTo(context, item, remapFolderPath(item.folderPath, folder.path, newFolderPath))
            }
            refresh()
            clearSelection()
        }
    }

    fun moveFolder(folder: FolderNode, destinationParentPath: String) {
        viewModelScope.launch {
            val context: Context = getApplication()
            val newFolderPath = if (destinationParentPath.isEmpty()) folder.name else "$destinationParentPath/${folder.name}"
            val copiedItems = folder.allItemsRecursive().filter { item ->
                val target = remapFolderPath(item.folderPath, folder.path, newFolderPath)
                copyMediaTo(context, item, target) != null
            }
            trashOriginals(copiedItems)
            refresh()
            clearSelection()
        }
    }

    fun renameFolder(folder: FolderNode, newName: String) {
        viewModelScope.launch {
            val context: Context = getApplication()
            val lastSlash = folder.path.lastIndexOf('/')
            val parentPath = if (lastSlash < 0) "" else folder.path.substring(0, lastSlash)
            val newFolderPath = if (parentPath.isEmpty()) newName else "$parentPath/$newName"

            val copiedItems = folder.allItemsRecursive().filter { item ->
                val target = remapFolderPath(item.folderPath, folder.path, newFolderPath)
                copyMediaTo(context, item, target) != null
            }
            trashOriginals(copiedItems)

            folderCovers.value[folder.path]?.let { cover ->
                prefs.setFolderCover(folder.path, null)
                prefs.setFolderCover(newFolderPath, cover)
            }
            refresh()
            clearSelection()
        }
    }

    /** Combined move covering both selected media items and selected folders in one destination pick. */
    fun moveSelectionTo(mediaItems: List<MediaItem>, folders: List<FolderNode>, destinationPath: String) {
        if (mediaItems.isNotEmpty()) moveMediaItems(mediaItems, destinationPath)
        folders.forEach { moveFolder(it, destinationPath) }
    }

    fun copySelectionTo(mediaItems: List<MediaItem>, folders: List<FolderNode>, destinationPath: String) {
        if (mediaItems.isNotEmpty()) copyMediaItems(mediaItems, destinationPath)
        folders.forEach { copyFolder(it, destinationPath) }
    }

    fun deleteSelection(mediaItems: List<MediaItem>, folders: List<FolderNode>, skipTrash: Boolean) {
        val allItems = mediaItems + folders.flatMap { it.allItemsRecursive() }
        deleteMediaItems(allItems, skipTrash)
        viewModelScope.launch { folders.forEach { prefs.setFolderCover(it.path, null) } }
    }

    private fun remapFolderPath(itemFolderPath: String, oldPrefix: String, newPrefix: String): String =
        if (itemFolderPath == oldPrefix) newPrefix else newPrefix + itemFolderPath.removePrefix(oldPrefix)
}
