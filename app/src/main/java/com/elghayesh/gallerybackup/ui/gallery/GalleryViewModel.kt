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
import com.elghayesh.gallerybackup.data.settings.FolderGroupSetting
import com.elghayesh.gallerybackup.data.settings.FolderSortSetting
import com.elghayesh.gallerybackup.data.settings.GalleryPreferencesRepository
import com.elghayesh.gallerybackup.data.settings.SortCriterion
import com.elghayesh.gallerybackup.data.settings.ThemeMode
import com.elghayesh.gallerybackup.data.settings.ViewType
import com.elghayesh.gallerybackup.sync.MediaChangeSignal
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    // Only one trash/delete-consent flow is allowed in flight at a time -- trashMutex serializes
    // every caller (direct delete, and copy-then-trash for move/rename) through this single set of
    // pending* fields. Without it, two overlapping calls (e.g. a fast double-tap re-firing a
    // rename before the first run's system consent dialog even appeared) would stomp on each
    // other's pendingDeleteIds/pendingDeleteIsSoft, and the CONFLATED deleteConsentChannel would
    // silently drop whichever consent request lost the race -- exactly the kind of thing that
    // leaves a rename/move only half-applied, with stray copies left behind and the wrong batch of
    // originals trashed.
    private val trashMutex = Mutex()
    private var pendingDeleteIds: List<Long> = emptyList()
    private var pendingDeleteIsSoft: Boolean = false
    private var pendingDeleteResult: CompletableDeferred<Boolean>? = null

    // Guards renameMediaItem/renameFolder against re-entrant double-invocation (e.g. a fast
    // double-tap on the rename dialog's confirm button firing before the dialog has closed) --
    // each rename copies bytes into a new file and trashes the original, so running it twice
    // concurrently on the same target duplicates the copy before trashMutex ever gets involved.
    private val renameMutex = Mutex()

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

    /** True for the whole duration of [refresh] -- the fast MediaStore scan AND the slower
     * EXIF date-refinement and unindexed-file rescan passes that follow it -- unlike [isLoading],
     * which deliberately flips back to false the moment the fast scan alone is done (see
     * [refresh]'s own doc comment for why). Drives a small, persistent top-bar spinner so there's
     * always some visible sign a background update is still happening, without reintroducing the
     * "blocked on Scanning..." feel [isLoading] was narrowed to avoid. */
    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

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
    val folderThumbnailWidth: StateFlow<Int> =
        prefs.folderThumbnailWidth.stateIn(viewModelScope, SharingStarted.Eagerly, 48)
    val mediaThumbnailWidth: StateFlow<Int> =
        prefs.mediaThumbnailWidth.stateIn(viewModelScope, SharingStarted.Eagerly, 48)
    val themeMode: StateFlow<ThemeMode> =
        prefs.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val accentColor: StateFlow<AccentColor> =
        prefs.accentColor.stateIn(viewModelScope, SharingStarted.Eagerly, AccentColor.BLUE)
    val folderSort: StateFlow<FolderSortSetting> =
        prefs.folderSort.stateIn(viewModelScope, SharingStarted.Eagerly, FolderSortSetting(SortCriterion.NAME, true))
    val folderSortOverrides: StateFlow<Map<String, FolderSortSetting>> =
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
    val folderGroupSettings: StateFlow<Map<String, FolderGroupSetting>> =
        prefs.folderGroupSettings.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val appLockEnabled: StateFlow<Boolean> =
        prefs.appLockEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val lockedFolders: StateFlow<Set<String>> =
        prefs.lockedFolders.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val lockHiddenItems: StateFlow<Boolean> =
        prefs.lockHiddenItems.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Folders unlocked (biometric/PIN) so far THIS app session -- not persisted, so every folder
     * needs re-unlocking again the next time the app itself is opened. */
    private val _unlockedFolderPaths = MutableStateFlow<Set<String>>(emptySet())
    val unlockedFolderPaths: StateFlow<Set<String>> = _unlockedFolderPaths.asStateFlow()

    fun markFolderUnlocked(path: String) {
        _unlockedFolderPaths.value = _unlockedFolderPaths.value + path
    }

    /** Whether hidden items have been unlocked (biometric/PIN) so far this app session -- see
     * [unlockedFolderPaths]'s own doc comment for why this resets every fresh app session. */
    private val _hiddenItemsUnlockedThisSession = MutableStateFlow(false)
    val hiddenItemsUnlockedThisSession: StateFlow<Boolean> = _hiddenItemsUnlockedThisSession.asStateFlow()

    fun markHiddenItemsUnlocked() {
        _hiddenItemsUnlockedThisSession.value = true
    }

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setAppLockEnabled(enabled) }
    }

    fun setFolderLocked(path: String, locked: Boolean) {
        viewModelScope.launch { prefs.setFolderLocked(path, locked) }
    }

    fun setLockHiddenItems(value: Boolean) {
        viewModelScope.launch { prefs.setLockHiddenItems(value) }
    }

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

    /** Runs the fast, MediaStore-only scan first and publishes it immediately -- so the gallery
     * (and any in-flight pull-to-refresh spinner) reflects the latest MediaStore state as soon as
     * that alone is ready, rather than sitting on the OLD data while waiting on either of the two
     * slower background passes below. Old content stays on screen the entire time either way:
     * none of the three scans ever clears [_rawRoot] before its own replacement is ready.
     *
     * [MediaRepository.refineDateTakenFromExif] runs next, without blocking [isLoading] -- it's
     * what corrects "date taken" from the fast scan's rough MediaStore-based guess to each photo's
     * own real EXIF capture time (see its doc comment for why that matters, and how it persists
     * what it finds so this only ever costs a per-photo file-open once, ever, per photo -- not on
     * every single refresh). A folder sorted/grouped by date taken can briefly show the fast,
     * rougher order right after a cold start, then silently snap to the correct one once this
     * finishes -- far better than leaving the whole gallery blocked on "Scanning..." for however
     * long a large library's full EXIF pass takes. Deliberately runs BEFORE
     * [MediaRepository.rescanUnindexedMedia] (a full filesystem walk that almost always finds
     * nothing new, since MediaStore's own scanner already indexes almost everything already) --
     * this is the far more commonly useful of the two background passes, so it shouldn't have to
     * wait on the rarer one. Safe to run in either order regardless: [MediaRepository.scanFolderTree]
     * re-applies every already-resolved date from persisted storage on its own next call, so a
     * subsequent scan from [rescanUnindexedMedia] finding new files can't undo this pass's work.
     *
     * [MediaRepository.rescanUnindexedMedia] runs last. It waits on a callback from Android's own
     * system media-scanner service that (rarely, but really) never fires -- bounded to at most 15
     * seconds internally for exactly that reason -- so running it last, after the far more useful
     * date-refinement pass, means that rare stall can no longer hold up date refinement itself
     * the way it did when the two were the other way around. */
    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _isUpdating.value = true
            _rawRoot.value = repository.scanFolderTree()
            _isLoading.value = false
            _rawRoot.value?.let { current ->
                _rawRoot.value = repository.refineDateTakenFromExif(current)
            }
            if (repository.rescanUnindexedMedia()) {
                _rawRoot.value = repository.scanFolderTree()
            }
            _isUpdating.value = false
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
    fun setFolderThumbnailWidth(widthDp: Int) = viewModelScope.launch { prefs.setFolderThumbnailWidth(widthDp) }
    fun setMediaThumbnailWidth(widthDp: Int) = viewModelScope.launch { prefs.setMediaThumbnailWidth(widthDp) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(mode) }
    fun setAccentColor(color: AccentColor) = viewModelScope.launch { prefs.setAccentColor(color) }
    /** Applies [setting] either as [path]'s own override ([thisFolderOnly] true, from the sort
     * dialog's "use for this folder only" checkbox) or as the new global default for every folder
     * without its own override (also clearing any override sitting at [path] itself, so the
     * change is visible immediately where it was made). */
    fun setFolderSort(setting: FolderSortSetting, thisFolderOnly: Boolean, path: String) {
        viewModelScope.launch {
            if (thisFolderOnly) {
                prefs.setFolderSortOverride(path, setting)
            } else {
                prefs.setFolderSort(setting)
                prefs.clearFolderSortOverride(path)
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
    fun setFolderGroupSetting(path: String, setting: FolderGroupSetting) =
        viewModelScope.launch { prefs.setFolderGroupSetting(path, setting) }

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
            val preRSoftTrash = !skipTrash && Build.VERSION.SDK_INT < Build.VERSION_CODES.R
            val approved = requestTrash(items, skipTrash)
            if (approved && !preRSoftTrash) refresh()
            clearSelection()
        }
    }

    /**
     * Trashes (or, if [skipTrash], permanently deletes) [items]' underlying MediaStore rows --
     * used both for a direct delete and, by [trashOriginals], for the "trash the originals" half
     * of a copy-then-trash move/rename. Every caller funnels through [trashMutex], so only one
     * trash/delete-consent flow is ever in flight: see the field doc on [trashMutex] for why that
     * matters. Suspends until the whole thing -- including the async system consent dialog on
     * Android 11+, resolved externally via [onDeleteConfirmed] -- has actually completed, so a
     * caller's own subsequent [refresh] reflects the real end state (originals actually gone)
     * rather than the in-between moment where a copy and its not-yet-trashed original both exist.
     * Returns false if the user cancelled the system dialog -- nothing was trashed/deleted.
     */
    private suspend fun requestTrash(items: List<MediaItem>, skipTrash: Boolean): Boolean {
        if (items.isEmpty()) return true
        return trashMutex.withLock {
            if (!skipTrash && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                trashRepository.trash(items.map { it.id })
                return@withLock true
            }
            when (val result = trashManager.requestDelete(items.map { it.uri }, skipTrash = skipTrash)) {
                is DeleteResult.ConsentRequired -> {
                    val deferred = CompletableDeferred<Boolean>()
                    pendingDeleteIds = items.map { it.id }
                    pendingDeleteIsSoft = !skipTrash
                    pendingDeleteResult = deferred
                    deleteConsentChannel.send(result.pendingIntent)
                    deferred.await()
                }
                DeleteResult.Deleted -> {
                    // Only reachable pre-R (a hard delete there runs synchronously inside
                    // TrashManager with no consent step) -- R+ always returns ConsentRequired for
                    // a non-empty list.
                    if (skipTrash) trashRepository.forget(items.map { it.id }) else trashRepository.trash(items.map { it.id })
                    true
                }
                is DeleteResult.Error -> false
            }
        }
    }

    /** Call after the system dialog launched from [deleteConsentRequests] resolves. [approved]
     * is false if the user cancelled it -- MediaStore never touched the files in that case, so
     * neither should MediaHub's own trash bookkeeping; the pending soft-trash or permanent
     * delete just never happened. Resolves whichever [requestTrash] call is currently awaiting
     * [pendingDeleteResult] -- trashMutex guarantees there's ever at most one. */
    fun onDeleteConfirmed(approved: Boolean) {
        viewModelScope.launch {
            if (approved) {
                if (pendingDeleteIsSoft) {
                    trashRepository.trash(pendingDeleteIds)
                } else {
                    trashRepository.forget(pendingDeleteIds)
                }
            }
            pendingDeleteIds = emptyList()
            pendingDeleteResult?.complete(approved)
            pendingDeleteResult = null
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
     * Delegates to [requestTrash] (skipTrash = false) so it shares the same single serialized
     * pending-consent slot as a direct delete, and suspends until trashing has actually resolved.
     */
    private suspend fun trashOriginals(items: List<MediaItem>): Boolean = requestTrash(items, skipTrash = false)

    /** Copies [items] into [destinationFolderPath], leaving the originals in place. */
    fun copyMediaItems(items: List<MediaItem>, destinationFolderPath: String) {
        viewModelScope.launch {
            val context: Context = getApplication()
            for (item in items) copyMediaTo(context, item, destinationFolderPath)
            refresh()
            clearSelection()
        }
    }

    /**
     * Copies [items] into [destinationFolderPath], then trashes the originals -- waiting for the
     * trash to actually finish (including any system consent dialog) before refreshing, so the
     * gallery's next state reflects the real end result instead of the moment in between where
     * both the copy and the not-yet-trashed original exist at once.
     */
    fun moveMediaItems(items: List<MediaItem>, destinationFolderPath: String) {
        viewModelScope.launch {
            val context: Context = getApplication()
            val copiedItems = items.filter { copyMediaTo(context, it, destinationFolderPath) != null }
            trashOriginals(copiedItems)
            refresh()
            clearSelection()
        }
    }

    /**
     * Renames [item] by copying its bytes into a new file with [newDisplayName] and trashing the
     * original. Guarded by [renameMutex] -- a rename that's already in flight for some item makes
     * a second, re-entrant rename call (e.g. a fast double-tap on the rename dialog's confirm
     * button before it closes) a silent no-op instead of racing the first one's copy.
     */
    fun renameMediaItem(item: MediaItem, newDisplayName: String) {
        viewModelScope.launch {
            if (!renameMutex.tryLock()) return@launch
            try {
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
            } finally {
                renameMutex.unlock()
            }
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

    /**
     * Renames [folder] to [newName]. Tries a real, atomic on-disk directory rename first (see
     * [MediaRepository.renameFolderInPlace]) -- no copying, no MediaStore trash-consent step, and
     * no way to end up with both the old and new folder existing at once. Only falls back to the
     * old copy-every-item-then-trash-the-originals approach when that's not possible (All files
     * access not granted) -- that approach's own copy step runs unconditionally before the
     * trash-consent step, so a denied/dismissed consent dialog could previously leave the
     * original folder fully intact AND a full duplicate under the new name, which is exactly the
     * "renamed folder" duplicating instead of renaming that was reported.
     *
     * Guarded by [renameMutex] for the same reason as [renameMediaItem] -- a re-entrant call while
     * one is already in flight (a fast double-tap on the rename dialog's confirm button) is a
     * silent no-op instead of running the rename twice concurrently.
     */
    fun renameFolder(folder: FolderNode, newName: String) {
        viewModelScope.launch {
            if (!renameMutex.tryLock()) return@launch
            try {
                val lastSlash = folder.path.lastIndexOf('/')
                val parentPath = if (lastSlash < 0) "" else folder.path.substring(0, lastSlash)
                val newFolderPath = if (parentPath.isEmpty()) newName else "$parentPath/$newName"

                if (!repository.renameFolderInPlace(folder.path, newName)) {
                    val context: Context = getApplication()
                    val copiedItems = folder.allItemsRecursive().filter { item ->
                        val target = remapFolderPath(item.folderPath, folder.path, newFolderPath)
                        copyMediaTo(context, item, target) != null
                    }
                    trashOriginals(copiedItems)
                }

                folderCovers.value[folder.path]?.let { cover ->
                    prefs.setFolderCover(folder.path, null)
                    prefs.setFolderCover(newFolderPath, cover)
                }
                refresh()
                clearSelection()
            } finally {
                renameMutex.unlock()
            }
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

    /**
     * Rewrites [itemFolderPath] (an item's real, raw MediaStore folder path) so its [oldPrefix]
     * segment (a folder being moved/renamed) becomes [newPrefix], preserving whatever subfolder
     * segments come after it. Both sides are trimmed of leading/trailing slashes before comparing
     * -- MediaStore's own RELATIVE_PATH column (what [MediaItem.folderPath] is read from on
     * Android 10+) always carries a trailing slash, e.g. "Root/Sub/Nested/", while [FolderNode]'s
     * own [FolderNode.path] never does, e.g. "Root/Sub/Nested"; comparing those directly without
     * normalizing first made [itemFolderPath] == [oldPrefix] false even for an item genuinely
     * inside the folder being renamed. The join is always done with an explicit "/" rather than by
     * relying on whatever separator characters happened to survive in the leftover suffix, so this
     * can never glue [newPrefix] straight onto an unrelated path with no separator between them --
     * the concrete, visible symptom that was actually reported (a renamed folder's on-disk name
     * ending up as the new name concatenated directly onto an ancestor folder's name).
     */
    private fun remapFolderPath(itemFolderPath: String, oldPrefix: String, newPrefix: String): String {
        val normalizedItem = itemFolderPath.trim('/')
        val normalizedOld = oldPrefix.trim('/')
        val suffix = normalizedItem.removePrefix(normalizedOld).trim('/')
        return if (suffix.isEmpty()) newPrefix else "$newPrefix/$suffix"
    }
}
