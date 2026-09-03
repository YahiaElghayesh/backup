package com.elghayesh.gallerybackup.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elghayesh.gallerybackup.data.media.FolderNode
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.data.media.allItemsRecursive
import com.elghayesh.gallerybackup.data.media.findNode
import com.elghayesh.gallerybackup.data.media.latestModifiedSec
import com.elghayesh.gallerybackup.data.media.promotedChildren
import com.elghayesh.gallerybackup.data.media.promotionAwareCoverUri
import com.elghayesh.gallerybackup.data.media.promotionAwareItemCount
import com.elghayesh.gallerybackup.data.settings.FolderCover
import com.elghayesh.gallerybackup.data.settings.FolderSortOrder
import com.elghayesh.gallerybackup.data.settings.ViewType
import com.elghayesh.gallerybackup.ui.common.CreateFolderDialog
import com.elghayesh.gallerybackup.ui.common.FolderCoverDialog
import com.elghayesh.gallerybackup.ui.common.FolderTreePickerDialog
import com.elghayesh.gallerybackup.ui.common.MediaActionBar
import com.elghayesh.gallerybackup.ui.common.PropertiesDialog
import com.elghayesh.gallerybackup.ui.common.RenameDialog
import com.elghayesh.gallerybackup.ui.common.rememberDeleteRequester
import com.elghayesh.gallerybackup.ui.common.shareMedia
import java.util.concurrent.TimeUnit

private enum class FolderTransferMode { MOVE, COPY }

/** LCM of the column counts 2..6 the folder/media size settings allow, so both always divide evenly. */
private const val GRID_SPAN_UNITS = 60

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    path: String,
    viewModel: GalleryViewModel,
    onOpenFolder: (String) -> Unit,
    onOpenMedia: (path: String, index: Int) -> Unit,
    onOpenGallerySettings: () -> Unit,
    onOpenBackupSettings: () -> Unit,
    onOpenTrash: () -> Unit,
    onEditPhoto: (path: String, index: Int) -> Unit,
    onEditVideo: (path: String, index: Int) -> Unit,
    onNavigateUp: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    LaunchedEffect(path) { viewModel.clearSelection() }
    val context = LocalContext.current

    val visibleRoot by viewModel.visibleRoot.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val folderViewType by viewModel.folderViewType.collectAsState()
    val mediaViewType by viewModel.mediaViewType.collectAsState()
    val folderGridColumns by viewModel.folderGridColumns.collectAsState()
    val mediaGridColumns by viewModel.mediaGridColumns.collectAsState()
    val folderRowSize by viewModel.folderRowSize.collectAsState()
    val mediaRowSize by viewModel.mediaRowSize.collectAsState()
    val folderSort by viewModel.folderSort.collectAsState()
    val showHidden by viewModel.showHidden.collectAsState()
    val hiddenFolders by viewModel.hiddenFolders.collectAsState()
    val hiddenMediaIds by viewModel.hiddenMediaIds.collectAsState()
    val includedFolders by viewModel.includedFolders.collectAsState()
    val selectedMediaIds by viewModel.selectedMediaIds.collectAsState()
    val selectedFolderPaths by viewModel.selectedFolderPaths.collectAsState()
    val folderCovers by viewModel.folderCovers.collectAsState()
    val favoriteMediaIds by viewModel.favoriteMediaIds.collectAsState()
    val node = visibleRoot?.findNode(path)

    // A pinned folder is promoted out of its real parent's listing wherever that parent is shown
    // (so it isn't duplicated in two places) and surfaces instead as its own tile on the gallery's
    // home page. This applies uniformly at any depth via FolderNode.promotedChildren -- a folder's
    // own real children never include one that's pinned elsewhere. The root additionally goes fully
    // opt-in the moment anything's been pinned: once includedFolders isn't empty, its own real
    // top-level children stop showing automatically too, and the tile list becomes purely whatever
    // was pinned. Before anything's ever been pinned, the root falls back to showing its real
    // top-level children as usual, so a fresh install isn't an empty gallery.
    val folders = if (node != null) {
        val ownChildren = when {
            path.isNotEmpty() -> node.promotedChildren(includedFolders)
            includedFolders.isEmpty() -> node.children.values.toList()
            else -> emptyList()
        }
        val pinnedExtras = if (path.isEmpty()) includedFolders.mapNotNull { visibleRoot?.findNode(it) } else emptyList()
        sortedFolders(ownChildren + pinnedExtras, folderSort, includedFolders)
    } else {
        emptyList()
    }
    val media = node?.items?.sortedByDescending { it.dateModifiedSec } ?: emptyList()
    val selectedItems = media.filter { it.id in selectedMediaIds }
    val selectedFolderNodes = folders.filter { it.path in selectedFolderPaths }
    val isSelectionMode = selectedMediaIds.isNotEmpty() || selectedFolderPaths.isNotEmpty()
    val totalSelectedCount = selectedItems.size + selectedFolderNodes.size

    BackHandler(enabled = isSelectionMode) { viewModel.clearSelection() }

    var overflowExpanded by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var transferMode by remember { mutableStateOf<FolderTransferMode?>(null) }
    var showProperties by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var coverDialogFor by remember { mutableStateOf<FolderNode?>(null) }

    val requestDelete = rememberDeleteRequester(viewModel)
    val gridState = rememberLazyGridState()
    var isDragSelecting by remember { mutableStateOf(false) }

    if (showSortDialog) {
        SortDialog(current = folderSort, onSelect = { viewModel.setFolderSort(it) }, onDismiss = { showSortDialog = false })
    }
    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onConfirm = { name -> viewModel.createFolder(path, name); showCreateFolderDialog = false },
            onDismiss = { showCreateFolderDialog = false },
        )
    }
    coverDialogFor?.let { folder ->
        FolderCoverDialog(
            folderName = folder.name,
            current = folderCovers[folder.path],
            onConfirm = { cover -> viewModel.setFolderCover(folder.path, cover); coverDialogFor = null },
            onDismiss = { coverDialogFor = null },
        )
    }
    if (showRenameDialog) {
        val initialName = when {
            selectedItems.size == 1 && selectedFolderNodes.isEmpty() ->
                selectedItems.first().displayName.substringBeforeLast('.', selectedItems.first().displayName)
            selectedFolderNodes.size == 1 && selectedItems.isEmpty() -> selectedFolderNodes.first().name
            else -> ""
        }
        RenameDialog(
            title = "Rename",
            initialName = initialName,
            onConfirm = { newName ->
                if (selectedItems.size == 1 && selectedFolderNodes.isEmpty()) {
                    viewModel.renameMediaItem(selectedItems.first(), newName)
                } else if (selectedFolderNodes.size == 1 && selectedItems.isEmpty()) {
                    viewModel.renameFolder(selectedFolderNodes.first(), newName)
                }
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }
    transferMode?.let { mode ->
        FolderTreePickerDialog(
            viewModel = viewModel,
            root = visibleRoot,
            title = if (mode == FolderTransferMode.MOVE) "Move to..." else "Copy to...",
            onPick = { destination ->
                if (mode == FolderTransferMode.MOVE) {
                    viewModel.moveSelectionTo(selectedItems, selectedFolderNodes, destination)
                } else {
                    viewModel.copySelectionTo(selectedItems, selectedFolderNodes, destination)
                }
                transferMode = null
            },
            onDismiss = { transferMode = null },
        )
    }
    if (showProperties && totalSelectedCount > 0) {
        val allItemsForProperties = selectedItems + selectedFolderNodes.flatMap { it.allItemsRecursive() }
        PropertiesDialog(items = allItemsForProperties, onDismiss = { showProperties = false })
    }

    Scaffold(
        topBar = {
            if (!isSelectionMode) {
                TopAppBar(
                    title = { Text(breadcrumbTitle(path)) },
                    navigationIcon = {
                        if (path.isNotEmpty()) {
                            IconButton(onClick = onNavigateUp) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Up")
                            }
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { overflowExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("Sort by...") },
                                    onClick = { overflowExpanded = false; showSortDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (folderViewType == ViewType.GRID) "Folders as list" else "Folders as grid") },
                                    onClick = {
                                        viewModel.setFolderViewType(if (folderViewType == ViewType.GRID) ViewType.LIST else ViewType.GRID)
                                        overflowExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (mediaViewType == ViewType.GRID) "Photos/videos as list" else "Photos/videos as grid") },
                                    onClick = {
                                        viewModel.setMediaViewType(if (mediaViewType == ViewType.GRID) ViewType.LIST else ViewType.GRID)
                                        overflowExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (showHidden) "Hide hidden items" else "Show hidden items") },
                                    onClick = { viewModel.setShowHidden(!showHidden); overflowExpanded = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("New folder here") },
                                    onClick = { overflowExpanded = false; showCreateFolderDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Rescan device") },
                                    onClick = { viewModel.refresh(); overflowExpanded = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Gallery settings") },
                                    onClick = { overflowExpanded = false; onOpenGallerySettings() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Backup settings") },
                                    onClick = { overflowExpanded = false; onOpenBackupSettings() },
                                )
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("$totalSelectedCount selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (isSelectionMode) {
                val allSelectedHidden = totalSelectedCount > 0 &&
                    selectedItems.all { it.id in hiddenMediaIds } &&
                    selectedFolderNodes.all { it.path in hiddenFolders }
                BottomAppBar {
                    MediaActionBar(
                        onEdit = if (selectedItems.size == 1 && selectedFolderNodes.isEmpty()) {
                            {
                                val only = selectedItems.first()
                                val index = media.indexOf(only)
                                if (only.isVideo) onEditVideo(path, index) else onEditPhoto(path, index)
                            }
                        } else {
                            null
                        },
                        onShare = { shareMedia(context, selectedItems) },
                        onDelete = {
                            selectedFolderNodes.forEach { viewModel.setFolderCover(it.path, null) }
                            requestDelete(selectedItems + selectedFolderNodes.flatMap { it.allItemsRecursive() })
                        },
                        isFavorite = selectedItems.isNotEmpty() && selectedItems.all { it.id in favoriteMediaIds },
                        onToggleFavorite = if (selectedItems.isNotEmpty()) {
                            { viewModel.toggleFavorites(selectedItems.map { it.id }) }
                        } else {
                            null
                        },
                        onMoveTo = { transferMode = FolderTransferMode.MOVE },
                        onCopyTo = { transferMode = FolderTransferMode.COPY },
                        overflowActions = buildList {
                            add(
                                (if (allSelectedHidden) "Unhide" else "Hide") to {
                                    selectedItems.forEach { viewModel.setMediaHidden(it.id, !allSelectedHidden) }
                                    selectedFolderNodes.forEach { viewModel.setFolderHidden(it.path, !allSelectedHidden) }
                                },
                            )
                            if (totalSelectedCount == 1) {
                                add("Rename" to { showRenameDialog = true })
                            }
                            if (selectedFolderNodes.size == 1 && selectedItems.isEmpty()) {
                                add("Set cover" to { coverDialogFor = selectedFolderNodes.first() })
                            }
                            add("Properties" to { showProperties = true })
                        },
                    )
                }
            }
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(onClick = onOpenTrash) {
                    Icon(Icons.Filled.Delete, contentDescription = "Trash")
                }
            }
        },
    ) { padding ->
        // Pull-to-refresh is the "faster, on demand" rescan the user can trigger manually; automatic
        // rescans on device media changes are debounced in GalleryViewModel's init block.
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when {
                isLoading && visibleRoot == null -> {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Scanning your photos and videos...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                node == null -> {
                    EmptyState(
                        icon = Icons.Filled.FolderOff,
                        title = "Folder not found",
                        subtitle = "This folder may have been moved or deleted.",
                    )
                }
                folders.isEmpty() && media.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Filled.PhotoLibrary,
                        title = "Nothing here yet",
                        subtitle = "Photos and videos you add will show up here. Pull down to rescan anytime.",
                    )
                }
                else -> {
                    if (folderViewType == ViewType.GRID && mediaViewType == ViewType.GRID) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(folders, media) {
                                    // A single, unified gesture owns the whole down-to-up lifecycle for every
                                    // tile: a plain tap opens the item (or toggles it, once already selecting);
                                    // a long press selects the item under the finger and enters selection mode;
                                    // continuing to drag from that same long press extends the selection to
                                    // whatever else the finger passes over. Handling all three in one detector
                                    // (rather than a per-tile clickable racing a container drag detector) avoids
                                    // the two independently reacting to the same up event.
                                    val tapSlopPx = 18.dp.toPx()
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val downIndex = itemIndexAt(gridState, down.position)
                                        val longPress = awaitLongPressOrCancellation(down.id)
                                        if (longPress != null) {
                                            isDragSelecting = true
                                            downIndex?.let { selectAt(it, folders, media, viewModel) }
                                            var pointerId = down.id
                                            try {
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                                    if (!change.pressed) {
                                                        change.consume()
                                                        break
                                                    }
                                                    itemIndexAt(gridState, change.position)
                                                        ?.let { selectAt(it, folders, media, viewModel) }
                                                    change.consume()
                                                    pointerId = change.id
                                                }
                                            } finally {
                                                isDragSelecting = false
                                            }
                                        } else if (downIndex != null) {
                                            val stillDown = currentEvent.changes.any { it.id == down.id && it.pressed }
                                            val moved = currentEvent.changes.any { change ->
                                                change.id == down.id &&
                                                    (change.position - down.position).getDistance() > tapSlopPx
                                            }
                                            if (!stillDown && !moved) {
                                                openOrToggle(downIndex, folders, media, viewModel, path, onOpenFolder, onOpenMedia)
                                            }
                                        }
                                    }
                                },
                        ) {
                            LazyVerticalGrid(
                                state = gridState,
                                // A single, shared column-track count that folder and media tiles each span a
                                // different number of, so the two can have independent apparent column counts
                                // (GRID_SPAN_UNITS is divisible by every column count 2..6) within one grid.
                                columns = GridCells.Fixed(GRID_SPAN_UNITS),
                                contentPadding = PaddingValues(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                userScrollEnabled = !isDragSelecting,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                gridItems(
                                    folders,
                                    key = { "folder:${it.path}" },
                                    span = { GridItemSpan(GRID_SPAN_UNITS / folderGridColumns) },
                                ) { folder ->
                                    FolderGridTile(
                                        folder = folder,
                                        isHidden = folder.path in hiddenFolders,
                                        isSelected = folder.path in selectedFolderPaths,
                                        cover = folderCovers[folder.path],
                                        includedFolders = includedFolders,
                                    )
                                }
                                gridItemsIndexed(
                                    media,
                                    key = { _, item -> "media:${item.id}" },
                                    span = { _, _ -> GridItemSpan(GRID_SPAN_UNITS / mediaGridColumns) },
                                ) { _, item ->
                                    MediaGridTile(
                                        item = item,
                                        isHidden = item.id in hiddenMediaIds,
                                        isSelected = item.id in selectedMediaIds,
                                    )
                                }
                            }
                        }
                    } else {
                        // At least one of folders/media is in list view -- no unified drag-select
                        // grid gesture here (that only applies when both are grids); each row/tile
                        // gets its own tap/long-press instead, same as the all-list layout always has.
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            if (folderViewType == ViewType.GRID) {
                                items(
                                    folders.chunked(folderGridColumns),
                                    key = { row -> "folderRow:" + row.joinToString("|") { it.path } },
                                ) { row ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        row.forEach { folder ->
                                            Box(Modifier.weight(1f)) {
                                                FolderGridTile(
                                                    folder = folder,
                                                    isHidden = folder.path in hiddenFolders,
                                                    isSelected = folder.path in selectedFolderPaths,
                                                    cover = folderCovers[folder.path],
                                                    includedFolders = includedFolders,
                                                    onClick = {
                                                        if (isSelectionMode) {
                                                            viewModel.toggleFolderSelection(folder.path)
                                                        } else {
                                                            onOpenFolder(folder.path)
                                                        }
                                                    },
                                                    onLongClick = { viewModel.setFolderSelected(folder.path, true) },
                                                )
                                            }
                                        }
                                        repeat(folderGridColumns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            } else {
                                items(folders, key = { "folder:${it.path}" }) { folder ->
                                    FolderListRow(
                                        folder = folder,
                                        isHidden = folder.path in hiddenFolders,
                                        isSelected = folder.path in selectedFolderPaths,
                                        cover = folderCovers[folder.path],
                                        includedFolders = includedFolders,
                                        thumbnailSizeDp = folderRowSize,
                                        onClick = {
                                            if (isSelectionMode) {
                                                viewModel.toggleFolderSelection(folder.path)
                                            } else {
                                                onOpenFolder(folder.path)
                                            }
                                        },
                                        onLongClick = { viewModel.setFolderSelected(folder.path, true) },
                                    )
                                }
                            }
                            if (mediaViewType == ViewType.GRID) {
                                items(
                                    media.withIndex().toList().chunked(mediaGridColumns),
                                    key = { row -> "mediaRow:" + row.joinToString("|") { it.value.id.toString() } },
                                ) { row ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        row.forEach { (index, item) ->
                                            Box(Modifier.weight(1f)) {
                                                MediaGridTile(
                                                    item = item,
                                                    isHidden = item.id in hiddenMediaIds,
                                                    isSelected = item.id in selectedMediaIds,
                                                    onClick = {
                                                        if (isSelectionMode) {
                                                            viewModel.toggleMediaSelection(item.id)
                                                        } else {
                                                            onOpenMedia(path, index)
                                                        }
                                                    },
                                                    onLongClick = { viewModel.setMediaSelected(item.id, true) },
                                                )
                                            }
                                        }
                                        repeat(mediaGridColumns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            } else {
                                itemsIndexed(media, key = { _, item -> "media:${item.id}" }) { index, item ->
                                    MediaListRow(
                                        item = item,
                                        isHidden = item.id in hiddenMediaIds,
                                        isSelected = item.id in selectedMediaIds,
                                        thumbnailSizeDp = mediaRowSize,
                                        onClick = {
                                            if (isSelectionMode) {
                                                viewModel.toggleMediaSelection(item.id)
                                            } else {
                                                onOpenMedia(path, index)
                                            }
                                        },
                                        onLongClick = { viewModel.setMediaSelected(item.id, true) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Maps a drag/long-press position to which grid item (by its flattened Compose index) sits under it. */
private fun itemIndexAt(gridState: LazyGridState, position: Offset): Int? {
    for (info in gridState.layoutInfo.visibleItemsInfo) {
        val x = info.offset.x
        val y = info.offset.y
        if (position.x >= x && position.x <= x + info.size.width && position.y >= y && position.y <= y + info.size.height) {
            return info.index
        }
    }
    return null
}

/** Folders are declared first in the grid, then media -- this mirrors that ordering to resolve a flat index. */
private fun selectAt(flatIndex: Int, folders: List<FolderNode>, media: List<MediaItem>, viewModel: GalleryViewModel) {
    if (flatIndex < folders.size) {
        viewModel.setFolderSelected(folders[flatIndex].path, true)
    } else {
        media.getOrNull(flatIndex - folders.size)?.let { viewModel.setMediaSelected(it.id, true) }
    }
}

/**
 * A plain tap on a grid tile: opens the folder/media, or -- if already selecting (read live off
 * the ViewModel, never a stale composition snapshot, since this runs from inside a long-lived
 * gesture callback) -- toggles that item's selection instead.
 */
private fun openOrToggle(
    flatIndex: Int,
    folders: List<FolderNode>,
    media: List<MediaItem>,
    viewModel: GalleryViewModel,
    path: String,
    onOpenFolder: (String) -> Unit,
    onOpenMedia: (path: String, index: Int) -> Unit,
) {
    val isSelectionMode = viewModel.selectedMediaIds.value.isNotEmpty() || viewModel.selectedFolderPaths.value.isNotEmpty()
    if (flatIndex < folders.size) {
        val folder = folders[flatIndex]
        if (isSelectionMode) viewModel.toggleFolderSelection(folder.path) else onOpenFolder(folder.path)
    } else {
        val mediaIndex = flatIndex - folders.size
        media.getOrNull(mediaIndex)?.let { item ->
            if (isSelectionMode) viewModel.toggleMediaSelection(item.id) else onOpenMedia(path, mediaIndex)
        }
    }
}

private fun sortedFolders(folders: List<FolderNode>, order: FolderSortOrder, includedFolders: Set<String>): List<FolderNode> =
    when (order) {
        FolderSortOrder.NAME_ASC -> folders.sortedBy { it.name.lowercase() }
        FolderSortOrder.NAME_DESC -> folders.sortedByDescending { it.name.lowercase() }
        FolderSortOrder.DATE_DESC -> folders.sortedByDescending { it.latestModifiedSec() }
        FolderSortOrder.DATE_ASC -> folders.sortedBy { it.latestModifiedSec() }
        FolderSortOrder.COUNT_DESC -> folders.sortedByDescending { it.promotionAwareItemCount(includedFolders) }
        FolderSortOrder.COUNT_ASC -> folders.sortedBy { it.promotionAwareItemCount(includedFolders) }
    }

private fun breadcrumbTitle(path: String): String =
    if (path.isEmpty()) "Gallery" else path.substringAfterLast('/')

@Composable
private fun BoxScope.EmptyState(icon: ImageVector, title: String, subtitle: String) {
    Column(
        Modifier
            .align(Alignment.Center)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SortDialog(current: FolderSortOrder, onSelect: (FolderSortOrder) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort folders by") },
        text = {
            Column {
                FolderSortOrder.entries.forEach { order ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(order); onDismiss() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = order == current, onClick = { onSelect(order); onDismiss() })
                        Text(order.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun FolderCoverContent(folder: FolderNode, cover: FolderCover?, includedFolders: Set<String>) {
    if (cover != null) {
        Box(Modifier.fillMaxSize().background(Color(cover.colorSeed)), contentAlignment = Alignment.Center) {
            Text(
                text = cover.text,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(8.dp),
            )
        }
        return
    }
    val coverUri = folder.promotionAwareCoverUri(includedFolders)
    if (coverUri != null) {
        AsyncImage(
            model = coverUri,
            contentDescription = folder.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
    } else {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().padding(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A bottom-up fade used behind labels/badges drawn over a photo, so white text and icons stay
 * legible on any thumbnail without needing a flat, visually heavy scrim across the whole tile. */
private val ScrimBrush = Brush.verticalGradient(
    0f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.78f),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderGridTile(
    folder: FolderNode,
    isHidden: Boolean,
    isSelected: Boolean,
    cover: FolderCover?,
    includedFolders: Set<String>,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.medium
    Box(
        Modifier
            .aspectRatio(1f)
            .shadow(if (isSelected) 6.dp else 1.dp, shape, clip = false)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (onClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick) else Modifier,
            )
            .then(
                if (isSelected) {
                    Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .alpha(if (isHidden) 0.5f else 1f),
    ) {
        FolderCoverContent(folder, cover, includedFolders)
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(ScrimBrush)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                text = "${folder.name}  ·  ${folder.promotionAwareItemCount(includedFolders)}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isHidden) {
            Icon(
                Icons.Filled.VisibilityOff,
                contentDescription = "Hidden",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    .padding(4.dp)
                    .size(16.dp),
            )
        }
        if (isSelected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGridTile(
    item: MediaItem,
    isHidden: Boolean,
    isSelected: Boolean,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.small
    Box(
        Modifier
            .aspectRatio(1f)
            .shadow(if (isSelected) 6.dp else 0.dp, shape, clip = false)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (onClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick) else Modifier,
            )
            .then(
                if (isSelected) {
                    Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            )
            .alpha(if (isHidden) 0.5f else 1f),
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.isVideo) {
            Box(Modifier.align(Alignment.BottomEnd).fillMaxWidth().height(28.dp).background(ScrimBrush))
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = "Video",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.Center),
            )
            Text(
                text = formatDuration(item.durationMs),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
        if (isHidden) {
            Icon(
                Icons.Filled.VisibilityOff,
                contentDescription = "Hidden",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    .padding(3.dp)
                    .size(14.dp),
            )
        }
        if (isSelected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryListItem(
    thumbnailModel: Any?,
    icon: ImageVector?,
    cover: FolderCover?,
    title: String,
    subtitle: String,
    isHidden: Boolean,
    isSelected: Boolean,
    thumbnailSizeDp: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        Modifier.background(
            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .alpha(if (isHidden) 0.5f else 1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(thumbnailSizeDp.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(cover?.let { Color(it.colorSeed) } ?: MaterialTheme.colorScheme.surfaceVariant),
            ) {
                when {
                    cover != null -> Text(
                        cover.text,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.Center).padding(2.dp),
                        textAlign = TextAlign.Center,
                    )
                    thumbnailModel != null -> AsyncImage(
                        model = thumbnailModel,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.align(Alignment.Center))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isSelected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
            } else if (isHidden) {
                Icon(Icons.Filled.VisibilityOff, contentDescription = "Hidden", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
internal fun FolderListRow(
    folder: FolderNode,
    isHidden: Boolean,
    isSelected: Boolean,
    cover: FolderCover?,
    includedFolders: Set<String>,
    thumbnailSizeDp: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    GalleryListItem(
        thumbnailModel = folder.promotionAwareCoverUri(includedFolders),
        icon = Icons.Filled.Folder,
        cover = cover,
        title = folder.name,
        subtitle = "${folder.promotionAwareItemCount(includedFolders)} items",
        isHidden = isHidden,
        isSelected = isSelected,
        thumbnailSizeDp = thumbnailSizeDp,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

@Composable
private fun MediaListRow(
    item: MediaItem,
    isHidden: Boolean,
    isSelected: Boolean,
    thumbnailSizeDp: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    GalleryListItem(
        thumbnailModel = item.uri,
        icon = null,
        cover = null,
        title = item.displayName,
        subtitle = if (item.isVideo) "Video - ${formatDuration(item.durationMs)}" else "Photo",
        isHidden = isHidden,
        isSelected = isSelected,
        thumbnailSizeDp = thumbnailSizeDp,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

private fun formatDuration(durationMs: Long): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(durationMs)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
