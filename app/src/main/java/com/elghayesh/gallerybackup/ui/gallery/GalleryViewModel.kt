package com.elghayesh.gallerybackup.ui.gallery

import android.app.Application
import android.app.PendingIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elghayesh.gallerybackup.data.media.DeleteResult
import com.elghayesh.gallerybackup.data.media.FolderNode
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.data.media.MediaRepository
import com.elghayesh.gallerybackup.data.media.TrashManager
import com.elghayesh.gallerybackup.data.media.filtered
import com.elghayesh.gallerybackup.data.settings.AccentColor
import com.elghayesh.gallerybackup.data.settings.FolderSortOrder
import com.elghayesh.gallerybackup.data.settings.GalleryPreferencesRepository
import com.elghayesh.gallerybackup.data.settings.ThemeMode
import com.elghayesh.gallerybackup.data.settings.ViewType
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = MediaRepository(app)
    private val prefs = GalleryPreferencesRepository(app)
    private val trashManager = TrashManager(app)

    private val deleteConsentChannel = Channel<PendingIntent>(Channel.CONFLATED)
    val deleteConsentRequests: Flow<PendingIntent> = deleteConsentChannel.receiveAsFlow()

    private val _rawRoot = MutableStateFlow<FolderNode?>(null)

    /** The full, unfiltered scan -- used by backup folder selection, which should see everything. */
    val root: StateFlow<FolderNode?> = _rawRoot.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val viewType: StateFlow<ViewType> =
        prefs.viewType.stateIn(viewModelScope, SharingStarted.Eagerly, ViewType.GRID)
    val gridColumns: StateFlow<Int> =
        prefs.gridColumns.stateIn(viewModelScope, SharingStarted.Eagerly, 3)
    val themeMode: StateFlow<ThemeMode> =
        prefs.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val accentColor: StateFlow<AccentColor> =
        prefs.accentColor.stateIn(viewModelScope, SharingStarted.Eagerly, AccentColor.BLUE)
    val folderSort: StateFlow<FolderSortOrder> =
        prefs.folderSort.stateIn(viewModelScope, SharingStarted.Eagerly, FolderSortOrder.NAME_ASC)
    val excludedFolders: StateFlow<Set<String>> =
        prefs.excludedFolders.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val hiddenFolders: StateFlow<Set<String>> =
        prefs.hiddenFolders.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val hiddenMediaIds: StateFlow<Set<Long>> =
        prefs.hiddenMediaIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val showHidden: StateFlow<Boolean> =
        prefs.showHidden.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** [root] with excluded folders always removed, and hidden folders/items removed unless [showHidden] is on. */
    val visibleRoot: StateFlow<FolderNode?> = combine(
        _rawRoot,
        prefs.excludedFolders,
        prefs.hiddenFolders,
        prefs.hiddenMediaIds,
        prefs.showHidden,
    ) { raw, excluded, hiddenFolders, hiddenMedia, showHidden ->
        raw?.filtered(excluded, hiddenFolders, hiddenMedia, showHidden)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun loadIfNeeded() {
        if (_rawRoot.value != null || _isLoading.value) return
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _rawRoot.value = repository.scanFolderTree()
            _isLoading.value = false
        }
    }

    fun setViewType(type: ViewType) = viewModelScope.launch { prefs.setViewType(type) }
    fun setGridColumns(columns: Int) = viewModelScope.launch { prefs.setGridColumns(columns) }
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { prefs.setThemeMode(mode) }
    fun setAccentColor(color: AccentColor) = viewModelScope.launch { prefs.setAccentColor(color) }
    fun setFolderSort(order: FolderSortOrder) = viewModelScope.launch { prefs.setFolderSort(order) }
    fun setFolderExcluded(path: String, excluded: Boolean) =
        viewModelScope.launch { prefs.setFolderExcluded(path, excluded) }
    fun setFolderHidden(path: String, hidden: Boolean) =
        viewModelScope.launch { prefs.setFolderHidden(path, hidden) }
    fun setMediaHidden(id: Long, hidden: Boolean) =
        viewModelScope.launch { prefs.setMediaHidden(id, hidden) }
    fun setShowHidden(show: Boolean) = viewModelScope.launch { prefs.setShowHidden(show) }

    /**
     * Deletes [item]. On Android 11+ this always needs one round trip through a system
     * confirmation dialog (see [TrashManager]) -- the caller (an Activity) must launch
     * the [PendingIntent] sent on [deleteConsentRequests] and report back via
     * [onDeleteConfirmed]. Below Android 11 it deletes immediately.
     */
    fun deleteMedia(item: MediaItem) {
        viewModelScope.launch {
            when (val result = trashManager.requestDelete(listOf(item.uri))) {
                is DeleteResult.ConsentRequired -> deleteConsentChannel.send(result.pendingIntent)
                DeleteResult.Deleted -> refresh()
                is DeleteResult.Error -> Unit
            }
        }
    }

    /** Call after the user approves the system trash-confirmation dialog launched from [deleteConsentRequests]. */
    fun onDeleteConfirmed() {
        refresh()
    }
}
