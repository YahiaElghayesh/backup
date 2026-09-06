package com.elghayesh.gallerybackup.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.elghayesh.gallerybackup.R
import com.elghayesh.gallerybackup.data.media.FolderNode
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.data.media.allItemsRecursive
import com.elghayesh.gallerybackup.data.media.findNode
import com.elghayesh.gallerybackup.data.media.latestModifiedSec
import com.elghayesh.gallerybackup.data.media.promotedChildren
import com.elghayesh.gallerybackup.data.media.promotionAwareCoverUri
import com.elghayesh.gallerybackup.data.media.promotionAwareItemCount
import com.elghayesh.gallerybackup.data.settings.FolderCover
import com.elghayesh.gallerybackup.data.settings.FolderGroupSetting
import com.elghayesh.gallerybackup.data.settings.FolderSortOrder
import com.elghayesh.gallerybackup.data.settings.FolderSortOverride
import com.elghayesh.gallerybackup.data.settings.GroupCriterion
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
    val folderSortOverrides by viewModel.folderSortOverrides.collectAsState()
    val effectiveFolderSort = remember(path, folderSort, folderSortOverrides) {
        effectiveFolderSort(path, folderSort, folderSortOverrides)
    }
    val showHidden by viewModel.showHidden.collectAsState()
    val hiddenFolders by viewModel.hiddenFolders.collectAsState()
    val hiddenMediaIds by viewModel.hiddenMediaIds.collectAsState()
    val includedFolders by viewModel.includedFolders.collectAsState()
    val selectedMediaIds by viewModel.selectedMediaIds.collectAsState()
    val selectedFolderPaths by viewModel.selectedFolderPaths.collectAsState()
    val folderCovers by viewModel.folderCovers.collectAsState()
    val favoriteMediaIds by viewModel.favoriteMediaIds.collectAsState()
    val pinContentToBottom by viewModel.pinContentToBottom.collectAsState()
    val folderGroupSettings by viewModel.folderGroupSettings.collectAsState()
    val groupSetting = folderGroupSettings[path] ?: FolderGroupSetting(GroupCriterion.NONE, true)
    val dateDividersEnabled = groupSetting.criterion != GroupCriterion.NONE
    val node = visibleRoot?.findNode(path)

    // A pinned folder is promoted out of its real parent's listing wherever that parent is shown
    // (so it isn't duplicated in two places) and surfaces instead as its own tile on the gallery's
    // home page. This applies uniformly at any depth via FolderNode.promotedChildren -- a folder's
    // own real children never include one that's pinned elsewhere. The root additionally goes fully
    // opt-in the moment anything's been pinned: once includedFolders isn't empty, its own real
    // top-level children stop showing automatically too, and the tile list becomes purely whatever
    // was pinned. Before anything's ever been pinned, the root falls back to showing its real
    // top-level children as usual, so a fresh install isn't an empty gallery.
    // A "." at the end of the path means "just this folder's own items", distinct from the
    // folder's normal listing -- see the self-tile logic below and FolderNode.findNode.
    val isSelfView = path.endsWith("/.")
    val realFolders = if (node != null && !isSelfView) {
        val ownChildren = when {
            path.isNotEmpty() -> node.promotedChildren(includedFolders)
            includedFolders.isEmpty() -> node.children.values.toList()
            else -> emptyList()
        }
        val pinnedExtras = if (path.isEmpty()) includedFolders.mapNotNull { visibleRoot?.findNode(it) } else emptyList()
        sortedFolders(ownChildren + pinnedExtras, effectiveFolderSort, includedFolders)
    } else {
        emptyList()
    }
    // A folder that has both real subfolders and its own direct items gets an extra tile, named
    // the same as the folder, for just those items -- rather than mixing loose media in with the
    // subfolder tiles. Opening it (path + "/.") shows only that folder's own items, nothing else.
    val hasSelfTile = !isSelfView && path.isNotEmpty() && node != null && realFolders.isNotEmpty() && node.items.isNotEmpty()
    val folders = if (hasSelfTile) {
        val selfNode = node!!
        realFolders + FolderNode("$path/.", selfNode.name).apply { items.addAll(selfNode.items) }
    } else {
        realFolders
    }
    // Previously always hardcoded to newest-first regardless of the folder's own sort order/
    // override -- meaning changing "Sort by" did nothing at all for a folder made up of media
    // rather than subfolders, since only the (separate) folders list respected it. Applying the
    // exact same effectiveFolderSort here is also why MediaViewerScreen and the photo/video
    // editors independently recompute it too (see sortedMedia's own doc comment) -- an index
    // picked from this list has to resolve to the same item wherever else that index is used.
    val media = if (hasSelfTile) emptyList() else sortedMedia(node?.items ?: emptyList(), effectiveFolderSort)
    // Read fresh inside the drag-select pointerInput below without needing folders/media in its
    // key -- keying on them directly would restart that gesture's coroutine (and lose an
    // in-progress drag) the instant a selection change recomposes this screen and produces new
    // (even if content-equal) folders/media list instances.
    val currentFolders = rememberUpdatedState(folders)
    val currentMedia = rememberUpdatedState(media)
    val selectedItems = media.filter { it.id in selectedMediaIds }
    val selectedFolderNodes = folders.filter { it.path in selectedFolderPaths }
    val isSelectionMode = selectedMediaIds.isNotEmpty() || selectedFolderPaths.isNotEmpty()
    val totalSelectedCount = selectedItems.size + selectedFolderNodes.size
    val totalSelectableCount = folders.size + media.size

    BackHandler(enabled = isSelectionMode) { viewModel.clearSelection() }

    var overflowExpanded by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showGroupByDialog by remember { mutableStateOf(false) }
    var transferMode by remember { mutableStateOf<FolderTransferMode?>(null) }
    var showProperties by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var coverDialogFolders by remember { mutableStateOf<List<FolderNode>>(emptyList()) }

    val requestDelete = rememberDeleteRequester(viewModel)
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    var isDragSelecting by remember { mutableStateOf(false) }

    // "Pin content to the bottom" should only actually anchor content to the bottom when there's
    // little enough of it that the viewport isn't full -- pushing it down as far as it'll go
    // without changing order, the same trick a chat screen uses for a short conversation. Once
    // there's enough content to fill (or overflow) the screen, this must be a complete no-op:
    // normal top-anchored layout, normal scrolling, unchanged order -- someone opening an
    // overflowing folder must always land at its actual top, never at the bottom needing to
    // scroll up.
    //
    // Implemented via alignment + a max-height bound instead of reverseLayout: the grid/list is
    // given Modifier.heightIn(max = <viewport height>) and aligned to the bottom of its own
    // BoxWithConstraints. A LazyColumn/LazyVerticalGrid measured with a loose (not exact) max
    // height reports its OWN size as just enough for its content, up to that max -- so it
    // naturally shrink-wraps (and the bottom alignment then pins it low) when content doesn't
    // fill the viewport, and naturally clamps to the full max height (making the alignment a
    // no-op, since it already fills the box) when content overflows. That means there's no
    // separate "does it fit" check to get right, and nothing that needs to settle in after the
    // first layout pass -- unlike the previous reverseLayout-based approach, which needed an
    // async LaunchedEffect to measure fit (a likely source of the brief visual glitch on opening
    // a folder) and needed every item index remapped to undo the reversal. Content is always
    // declared in plain, unreversed order in both modes.
    //
    // Drag-select's own hit-testing (see dragSelectGesture below) reads each visible tile/row's
    // position from LazyGridState/LazyListState.layoutInfo, which reports every item's offset
    // relative to the grid/list's OWN top-left corner -- not relative to whatever ancestor
    // happens to contain it. The moment a short, bottom-aligned grid/list no longer starts at the
    // same point as the BoxWithConstraints around it (exactly what pin-to-bottom does for a
    // folder with few enough items to shrink-wrap), a gesture detector attached to that outer Box
    // instead of the grid/list itself is reading touch positions in the wrong frame: shifted
    // downward by however far the bottom alignment pushed the content, relative to what
    // layoutInfo is using. That mismatch is what made hovering land on the wrong tile -- or no
    // tile at all -- specifically for a pinned folder short enough to not fill the screen. The fix
    // is structural, not a coordinate correction: dragSelectGesture's pointerInput is attached
    // directly to the LazyVerticalGrid/LazyColumn's own modifier chain (see its call sites below),
    // so the positions it reads are always already in the exact same frame its hit-test reads
    // layoutInfo from, regardless of how -- or whether -- pin-to-bottom repositions that grid/list
    // within its parent.

    if (showSortDialog) {
        SortDialog(
            current = effectiveFolderSort,
            folderName = if (path.isEmpty()) "Gallery" else path.substringAfterLast('/'),
            onSelect = { order, scope -> viewModel.setFolderSort(order, scope, path) },
            onDismiss = { showSortDialog = false },
        )
    }
    if (showGroupByDialog) {
        GroupByDialog(
            current = groupSetting,
            onConfirm = { setting -> viewModel.setFolderGroupSetting(path, setting); showGroupByDialog = false },
            onDismiss = { showGroupByDialog = false },
        )
    }
    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onConfirm = { name -> viewModel.createFolder(path.removeSuffix("/."), name); showCreateFolderDialog = false },
            onDismiss = { showCreateFolderDialog = false },
        )
    }
    if (coverDialogFolders.isNotEmpty()) {
        FolderCoverDialog(
            folders = coverDialogFolders,
            current = if (coverDialogFolders.size == 1) folderCovers[coverDialogFolders.first().path] else null,
            onConfirm = { perFolder ->
                perFolder.forEach { (path, cover) -> viewModel.setFolderCover(path, cover) }
                coverDialogFolders = emptyList()
            },
            onDismiss = { coverDialogFolders = emptyList() },
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
                        IconButton(onClick = onOpenTrash) {
                            Icon(Icons.Filled.Delete, contentDescription = "Trash")
                        }
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
                                    text = { Text(if (showHidden) "Hide hidden items" else "Show hidden items") },
                                    onClick = { viewModel.setShowHidden(!showHidden); overflowExpanded = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("New folder here") },
                                    onClick = { overflowExpanded = false; showCreateFolderDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Group by...") },
                                    onClick = { overflowExpanded = false; showGroupByDialog = true },
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
                    title = { Text("$totalSelectedCount / $totalSelectableCount selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                folders.forEach { viewModel.setFolderSelected(it.path, true) }
                                media.forEach { viewModel.setMediaSelected(it.id, true) }
                            },
                        ) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
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
                            if (selectedFolderNodes.isNotEmpty() && selectedItems.isEmpty()) {
                                add(
                                    (if (selectedFolderNodes.size == 1) "Set cover" else "Set covers") to {
                                        coverDialogFolders = selectedFolderNodes
                                    },
                                )
                            }
                            add("Properties" to { showProperties = true })
                        },
                    )
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
                        BoxWithConstraints(
                            Modifier.fillMaxSize(),
                        ) {
                            LazyVerticalGrid(
                                state = gridState,
                                // A single, shared column-track count that folder and media tiles each span a
                                // different number of, so the two can have independent apparent column counts
                                // (GRID_SPAN_UNITS is divisible by every column count 2..6) within one grid.
                                columns = GridCells.Fixed(GRID_SPAN_UNITS),
                                // Extra end clearance so no tile ever renders behind the fast-scrollbar
                                // overlaid on top of this Box -- see FastScrollbar below.
                                contentPadding = PaddingValues(
                                    start = 4.dp,
                                    top = 4.dp,
                                    end = FAST_SCROLLBAR_CLEARANCE,
                                    bottom = 4.dp,
                                ),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                userScrollEnabled = !isDragSelecting,
                                modifier = Modifier
                                    .align(if (pinContentToBottom) Alignment.BottomStart else Alignment.TopStart)
                                    .fillMaxWidth()
                                    .then(
                                        if (pinContentToBottom) Modifier.heightIn(max = maxHeight) else Modifier.fillMaxHeight(),
                                    )
                                    // Each tile owns its own tap/long-press via combinedClickable
                                    // (onClick/onLongClick below); this pointerInput never
                                    // participates in tap-to-open, only in extending the selection
                                    // once a tile's own onLongClick has already fired (isDragSelecting
                                    // flips true) -- same reasoning as before. It's attached directly
                                    // to the grid itself, not a wrapping ancestor, specifically so its
                                    // hit-testing always agrees with pin-to-bottom -- see
                                    // dragSelectGesture's own doc comment and the pin-to-bottom
                                    // comment above for why that coupling matters.
                                    //
                                    // Keyed only on path (effectively constant for this screen's whole
                                    // lifetime) rather than on folders/media -- those are read live via
                                    // currentFolders/currentMedia instead, specifically so a selection
                                    // change mid-drag (which recomposes this screen and produces new
                                    // folders/media list instances) can never restart this gesture's
                                    // coroutine and drop an in-progress drag-select.
                                    .pointerInput(path) {
                                        dragSelectGesture(
                                            isDragSelecting = { isDragSelecting },
                                            setDragSelecting = { isDragSelecting = it },
                                            viewModel = viewModel,
                                        ) { position ->
                                            itemIndexAt(gridState, position)
                                                ?.let { folderOrMediaAt(it, currentFolders.value, currentMedia.value) }
                                        }
                                    },
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
                                        onClick = {
                                            if (isSelectionMode) {
                                                viewModel.toggleFolderSelection(folder.path)
                                            } else {
                                                onOpenFolder(folder.path)
                                            }
                                        },
                                        onLongClick = {
                                            isDragSelecting = true
                                            viewModel.setFolderSelected(folder.path, true)
                                        },
                                    )
                                }
                                if (dateDividersEnabled) {
                                    groupMedia(media, groupSetting).forEach { (label, group) ->
                                        item(key = "divider:$label", span = { GridItemSpan(GRID_SPAN_UNITS) }) {
                                            DateDividerLabel(label)
                                        }
                                        gridItemsIndexed(
                                            group,
                                            key = { _, item -> "media:${item.id}" },
                                            span = { _, _ -> GridItemSpan(GRID_SPAN_UNITS / mediaGridColumns) },
                                        ) { _, item ->
                                            MediaGridTile(
                                                item = item,
                                                isHidden = item.id in hiddenMediaIds,
                                                isSelected = item.id in selectedMediaIds,
                                                onClick = {
                                                    if (isSelectionMode) {
                                                        viewModel.toggleMediaSelection(item.id)
                                                    } else {
                                                        onOpenMedia(path, media.indexOf(item))
                                                    }
                                                },
                                                onLongClick = {
                                                    isDragSelecting = true
                                                    viewModel.setMediaSelected(item.id, true)
                                                },
                                            )
                                        }
                                    }
                                } else {
                                    gridItemsIndexed(
                                        media,
                                        key = { _, item -> "media:${item.id}" },
                                        span = { _, _ -> GridItemSpan(GRID_SPAN_UNITS / mediaGridColumns) },
                                    ) { index, item ->
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
                                            onLongClick = {
                                                isDragSelecting = true
                                                viewModel.setMediaSelected(item.id, true)
                                            },
                                        )
                                    }
                                }
                            }
                            FastScrollbar(
                                gridState = gridState,
                                media = media,
                                dateDividersEnabled = dateDividersEnabled,
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            )
                        }
                    } else {
                        // At least one of folders/media is in list view. Same as the grid+grid
                        // branch above: each row/tile owns its own tap/long-press via
                        // combinedClickable, and the wrapping Box's pointerInput below only takes
                        // over to extend the selection once a long-press has already fired.
                        //
                        // Pin-to-bottom sizing (heightIn(max) + bottom alignment) is applied to the
                        // LazyColumn itself below -- content here is always declared in plain,
                        // unreversed order (folders above media, both in their usual order).
                        val folderSection: LazyListScope.() -> Unit = {
                            if (folderViewType == ViewType.GRID) {
                                val rows = folders.chunked(folderGridColumns)
                                items(rows, key = { row -> "folderRow:" + row.joinToString("|") { it.path } }) { row ->
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
                                                    onLongClick = {
                                                        isDragSelecting = true
                                                        viewModel.setFolderSelected(folder.path, true)
                                                    },
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
                                        onLongClick = {
                                            isDragSelecting = true
                                            viewModel.setFolderSelected(folder.path, true)
                                        },
                                    )
                                }
                            }
                        }
                        val mediaSection: LazyListScope.() -> Unit = {
                            if (mediaViewType == ViewType.GRID) {
                                // Each date group is chunked into rows independently (rather than
                                // chunking the whole flat list and only then looking for where a
                                // month boundary falls) so a row never straddles two different
                                // months -- the last, possibly-partial row of a group already
                                // pads out with spacers the same way the final row of the whole
                                // list normally does.
                                val groupedRows: List<Pair<String?, List<IndexedValue<MediaItem>>>> =
                                    if (dateDividersEnabled) {
                                        groupMedia(media, groupSetting).flatMap { (label, group) ->
                                            val indexed = group.map { IndexedValue(media.indexOf(it), it) }
                                            listOf(label to emptyList<IndexedValue<MediaItem>>()) +
                                                indexed.chunked(mediaGridColumns).map { null to it }
                                        }
                                    } else {
                                        media.withIndex().toList().chunked(mediaGridColumns).map { null to it }
                                    }
                                items(
                                    groupedRows,
                                    key = { (label, row) ->
                                        if (label != null) "divider:$label" else "mediaRow:" + row.joinToString("|") { it.value.id.toString() }
                                    },
                                ) { (label, row) ->
                                    if (label != null) {
                                        DateDividerLabel(label)
                                    } else {
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
                                                        onLongClick = {
                                                            isDragSelecting = true
                                                            viewModel.setMediaSelected(item.id, true)
                                                        },
                                                    )
                                                }
                                            }
                                            repeat(mediaGridColumns - row.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                            } else {
                                val groupedItems: List<Pair<String?, IndexedValue<MediaItem>?>> =
                                    if (dateDividersEnabled) {
                                        groupMedia(media, groupSetting).flatMap { (label, group) ->
                                            listOf(label to null) + group.map { null to IndexedValue(media.indexOf(it), it) }
                                        }
                                    } else {
                                        media.withIndex().toList().map { null to it }
                                    }
                                items(
                                    groupedItems,
                                    key = { (label, entry) -> if (label != null) "divider:$label" else "media:${entry!!.value.id}" },
                                ) { (label, entry) ->
                                    if (label != null) {
                                        DateDividerLabel(label)
                                        return@items
                                    }
                                    val (index, item) = entry!!
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
                                        onLongClick = {
                                            isDragSelecting = true
                                            viewModel.setMediaSelected(item.id, true)
                                        },
                                    )
                                }
                            }
                        }
                        BoxWithConstraints(
                            Modifier.fillMaxSize(),
                        ) {
                            LazyColumn(
                                state = listState,
                                userScrollEnabled = !isDragSelecting,
                                // End clearance so no row renders behind the fast-scrollbar overlaid
                                // on top of this Box -- see FastScrollbar below.
                                contentPadding = PaddingValues(end = FAST_SCROLLBAR_CLEARANCE),
                                modifier = Modifier
                                    .align(if (pinContentToBottom) Alignment.BottomStart else Alignment.TopStart)
                                    .fillMaxWidth()
                                    .then(
                                        if (pinContentToBottom) Modifier.heightIn(max = maxHeight) else Modifier.fillMaxHeight(),
                                    )
                                    // See the grid+grid branch's own pointerInput above for why
                                    // this lives directly on the list itself rather than a
                                    // wrapping ancestor.
                                    .pointerInput(path) {
                                        val clearancePx = FAST_SCROLLBAR_CLEARANCE.toPx()
                                        dragSelectGesture(
                                            isDragSelecting = { isDragSelecting },
                                            setDragSelecting = { isDragSelecting = it },
                                            viewModel = viewModel,
                                        ) { position ->
                                            folderOrMediaAtListPosition(
                                                listState, position, size.width.toFloat(), clearancePx,
                                                currentFolders.value, currentMedia.value,
                                            )
                                        }
                                    },
                            ) {
                                folderSection()
                                mediaSection()
                            }
                            FastScrollbar(
                                listState = listState,
                                media = media,
                                dateDividersEnabled = dateDividersEnabled,
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The thumb's own visible width -- a slim pill like a native system scrollbar, not a thick bar
 * that draws the eye away from the actual photos. */
private val FAST_SCROLLBAR_THUMB_WIDTH = 4.dp

/** The drag target is wider than the visible thumb (invisibly) so it stays easy to grab with a
 * finger despite the thumb itself being slim -- the thumb is centered within this width. */
private val FAST_SCROLLBAR_TOUCH_WIDTH = 24.dp
private val FAST_SCROLLBAR_GUTTER = 6.dp

/** Gap between the invisible drag target's left edge and the current-date bubble shown while
 * dragging in a date-divided folder -- so the bubble never overlaps the thumb/touch zone itself. */
private val FAST_DATE_BUBBLE_GUTTER = 8.dp

/** How much end space the grid/list needs to reserve (as contentPadding) so no tile or row ever
 * renders behind [FastScrollbar] -- the invisible drag target's width plus a small gutter of clear
 * space between it and the content. */
private val FAST_SCROLLBAR_CLEARANCE = FAST_SCROLLBAR_TOUCH_WIDTH + FAST_SCROLLBAR_GUTTER

/** A far-right, drag-to-jump scrollbar for quickly moving through a long folder/media listing --
 * ordinary drag-to-scroll only covers a screenful at a time. Sized and positioned to never overlap
 * real content: [FAST_SCROLLBAR_CLEARANCE] is reserved as the grid/list's own end contentPadding,
 * so this whole bar (plus its gutter) sits in space no tile is ever drawn into. */
@Composable
private fun FastScrollbar(
    gridState: LazyGridState,
    media: List<MediaItem>,
    dateDividersEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val info = gridState.layoutInfo
    FastScrollbarTrack(
        firstVisibleIndex = gridState.firstVisibleItemIndex,
        visibleCount = info.visibleItemsInfo.size,
        totalCount = info.totalItemsCount,
        currentLabel = if (dateDividersEnabled) {
            currentDateGroupLabel(info.visibleItemsInfo.firstOrNull()?.key, media)
        } else {
            null
        },
        onDragToFraction = { fraction ->
            val total = gridState.layoutInfo.totalItemsCount
            if (total > 0) {
                // A continuous (fractional) target, split into a whole item index plus a
                // sub-item pixel offset within it, rather than rounding to the nearest whole
                // item -- rounding is what made the old version visibly jump in discrete
                // per-item steps instead of tracking the thumb's exact drag position.
                val continuousIndex = (fraction * total).coerceIn(0f, (total - 1).toFloat())
                val targetIndex = continuousIndex.toInt()
                val itemSizePx = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.height?.takeIf { it > 0 } ?: 1
                val withinItemOffsetPx = ((continuousIndex - targetIndex) * itemSizePx).roundToInt()
                scope.launch { gridState.scrollToItem(targetIndex, withinItemOffsetPx) }
            }
        },
        modifier = modifier,
    )
}

/** See the [LazyGridState] overload -- same idea, for the mixed grid/list branch's [LazyColumn]. */
@Composable
private fun FastScrollbar(
    listState: LazyListState,
    media: List<MediaItem>,
    dateDividersEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val info = listState.layoutInfo
    FastScrollbarTrack(
        firstVisibleIndex = listState.firstVisibleItemIndex,
        visibleCount = info.visibleItemsInfo.size,
        totalCount = info.totalItemsCount,
        currentLabel = if (dateDividersEnabled) {
            currentDateGroupLabel(info.visibleItemsInfo.firstOrNull()?.key, media)
        } else {
            null
        },
        onDragToFraction = { fraction ->
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) {
                val continuousIndex = (fraction * total).coerceIn(0f, (total - 1).toFloat())
                val targetIndex = continuousIndex.toInt()
                val itemSizePx = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.takeIf { it > 0 } ?: 1
                val withinItemOffsetPx = ((continuousIndex - targetIndex) * itemSizePx).roundToInt()
                scope.launch { listState.scrollToItem(targetIndex, withinItemOffsetPx) }
            }
        },
        modifier = modifier,
    )
}

/** The "<Month> <Year>" label (e.g. "September 2022") for whichever date group the topmost
 * visible grid/list item belongs to, read directly off that item's own key -- every divider and
 * media item's key already encodes exactly this (see the "divider:$label"/"media:$id"/
 * "mediaRow:$id|$id..." keys used when declaring grid/list items above), so reading it back here
 * can never drift out of sync with what's actually rendered the way re-deriving it from a flat
 * index would. Null for a folder row/tile (folders aren't dated) or once there's nothing visible
 * yet. */
private fun currentDateGroupLabel(key: Any?, media: List<MediaItem>): String? {
    val keyString = key as? String ?: return null
    return when {
        keyString.startsWith("divider:") -> keyString.removePrefix("divider:")
        keyString.startsWith("media:") -> {
            val id = keyString.removePrefix("media:").toLongOrNull()
            monthYearLabel(media.firstOrNull { it.id == id })
        }
        keyString.startsWith("mediaRow:") -> {
            val firstId = keyString.removePrefix("mediaRow:").substringBefore("|").toLongOrNull()
            monthYearLabel(media.firstOrNull { it.id == firstId })
        }
        else -> null
    }
}

private fun monthYearLabel(item: MediaItem?): String? {
    item ?: return null
    return java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(item.dateModifiedSec * 1000))
}

@Composable
private fun FastScrollbarTrack(
    firstVisibleIndex: Int,
    visibleCount: Int,
    totalCount: Int,
    currentLabel: String?,
    onDragToFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Nothing to jump to if everything's already on screen -- no bar needed (and no risk of a
    // near-invisible sliver thumb spanning the whole track).
    if (totalCount == 0 || visibleCount >= totalCount) return
    BoxWithConstraints(modifier.width(FAST_SCROLLBAR_TOUCH_WIDTH)) {
        val density = LocalDensity.current
        val trackHeightPx = with(density) { maxHeight.toPx() }
        val thumbHeightPx = trackHeightPx * (visibleCount.toFloat() / totalCount.toFloat()).coerceIn(0.08f, 1f)
        val scrollRange = (totalCount - visibleCount).coerceAtLeast(1)
        val positionFraction = (firstVisibleIndex.toFloat() / scrollRange).coerceIn(0f, 1f)
        val thumbOffsetPx = (trackHeightPx - thumbHeightPx) * positionFraction
        // Only shown while actively dragging the thumb -- like the reference file manager's date
        // bubble, it's a "where am I" readout for the fast-scroll gesture itself, not a permanent
        // fixture, so it shouldn't linger once the finger lifts or clutter ordinary scrolling.
        var isDragging by remember { mutableStateOf(false) }

        // Read through this (never the raw thumbHeightPx/trackHeightPx captured above) from
        // inside the gesture below -- see that pointerInput's own comment for why.
        val onDragTo = rememberUpdatedState { yPx: Float ->
            val usableTrack = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
            onDragToFraction(((yPx - thumbHeightPx / 2f) / usableTrack).coerceIn(0f, 1f))
        }

        // The full (wider, invisible) touch target handles the drag, so a finger doesn't need to
        // land precisely on the slim visible thumb to grab it.
        //
        // Keyed on Unit (never totalCount/visibleCount) -- those change continuously while
        // scrolling, since visibleCount fluctuates as items scroll past the viewport edge even
        // mid-drag. Keying pointerInput on either one used to cancel and restart this exact
        // gesture's coroutine the instant either changed, which -- because a restarted
        // pointerInput has no down event to resume from -- killed detectDragGestures right in the
        // middle of a drag: the thumb would stop responding after tracking the finger for a
        // single step, as if released, even though the finger was still down. rememberUpdatedState
        // is what lets the gesture stay keyed on Unit (never restarting) while still always
        // calling through to this composition's latest onDragTo, so the drag conversion math
        // never goes stale either.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset -> isDragging = true; onDragTo.value(offset.y) },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                    ) { change, _ ->
                        change.consume()
                        onDragTo.value(change.position.y)
                    }
                },
        )
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, thumbOffsetPx.roundToInt()) }
                .width(FAST_SCROLLBAR_THUMB_WIDTH)
                .height(with(density) { thumbHeightPx.toDp() })
                .clip(RoundedCornerShape(FAST_SCROLLBAR_THUMB_WIDTH / 2))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)),
        )
        if (isDragging && currentLabel != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbOffsetPx.roundToInt()) }
                    .height(with(density) { thumbHeightPx.toDp() })
                    // unbounded=true so the pill (usually taller than a thin thumb, especially
                    // for a long folder where the thumb itself is tiny) isn't squashed down to
                    // the thumb's own height -- it's still centered on the thumb's vertical
                    // midpoint, just free to be its own natural size.
                    .wrapContentHeight(Alignment.CenterVertically, unbounded = true)
                    .offset(x = -(FAST_SCROLLBAR_TOUCH_WIDTH + FAST_DATE_BUBBLE_GUTTER)),
            ) {
                Text(
                    text = currentLabel,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
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

/** Maps a grid's flat Compose index back to the folder/media item it belongs to (folders declared
 * first, then media). */
private fun folderOrMediaAt(
    flatIndex: Int,
    folders: List<FolderNode>,
    media: List<MediaItem>,
): Pair<FolderNode?, MediaItem?> {
    return if (flatIndex < folders.size) {
        folders[flatIndex] to null
    } else {
        null to media.getOrNull(flatIndex - folders.size)
    }
}

/** A folder or media item's identity for drag-select's own hover-path tracking (see
 * [applyDragHover]) -- independent of any index math, so it stays valid across both the flat-grid
 * and key-based list resolvers. */
private sealed class DragItemKey {
    data class Folder(val path: String) : DragItemKey()
    data class Media(val id: Long) : DragItemKey()
}

private fun DragItemKey.setSelected(viewModel: GalleryViewModel, selected: Boolean) {
    when (this) {
        is DragItemKey.Folder -> viewModel.setFolderSelected(path, selected)
        is DragItemKey.Media -> viewModel.setMediaSelected(id, selected)
    }
}

private fun dragItemKeyOf(pair: Pair<FolderNode?, MediaItem?>?): DragItemKey? = when {
    pair?.first != null -> DragItemKey.Folder(pair.first!!.path)
    pair?.second != null -> DragItemKey.Media(pair.second!!.id)
    else -> null
}

/**
 * Applies one drag-select hover position as a step in an undo-style path: hovering onto an item
 * not yet visited this drag selects it and appends it to [path]; hovering back onto an item
 * already earlier in [path] deselects everything visited after it and truncates back to that
 * point. That's what makes dragging out over a run of items and then dragging back the way you
 * came un-select them again, instead of every item the finger ever passed over staying selected
 * regardless of direction. Never deselects anything in [preExistingFolders]/[preExistingMedia] --
 * selected before this drag gesture even began -- even if the drag happens to pass back over it.
 */
private fun applyDragHover(
    pair: Pair<FolderNode?, MediaItem?>?,
    path: MutableList<DragItemKey>,
    preExistingFolders: Set<String>,
    preExistingMedia: Set<Long>,
    viewModel: GalleryViewModel,
) {
    val key = dragItemKeyOf(pair) ?: return
    val existingIndex = path.indexOf(key)
    if (existingIndex != -1) {
        while (path.size > existingIndex + 1) {
            val popped = path.removeAt(path.size - 1)
            val stillPreExisting = when (popped) {
                is DragItemKey.Folder -> popped.path in preExistingFolders
                is DragItemKey.Media -> popped.id in preExistingMedia
            }
            if (!stillPreExisting) popped.setSelected(viewModel, false)
        }
    } else {
        path.add(key)
        key.setSelected(viewModel, true)
    }
}

/**
 * The drag-to-extend-selection gesture shared identically by both the grid+grid branch (over its
 * LazyVerticalGrid) and the list/mixed branch (over its LazyColumn) -- see each call site for how
 * [hitTest] resolves a touch position into a folder/media item there.
 *
 * This must be attached directly to that lazy layout's own modifier chain, never to some wrapping
 * ancestor Box -- [hitTest] resolves a position using that layout's own LazyGridState/
 * LazyListState.layoutInfo, whose item offsets are always relative to the layout's OWN top-left
 * corner, not to whatever contains it. As long as this gesture is attached to that same node,
 * [PointerInputScope.awaitPointerEvent]'s reported positions are guaranteed to be in that exact
 * same frame no matter how the node ends up placed by its parent -- including "pin content to the
 * bottom" bottom-aligning a short grid/list partway down a taller viewport, which used to shift
 * the grid/list's own origin away from its parent Box's origin while a gesture attached to that
 * Box kept reading positions relative to the Box. That mismatch (not anything about which
 * direction is "up" or "down") is what made hovering resolve to the wrong tile, or no tile at all,
 * specifically for a folder short enough to shrink-wrap while pinned to the bottom.
 */
private suspend fun PointerInputScope.dragSelectGesture(
    isDragSelecting: () -> Boolean,
    setDragSelecting: (Boolean) -> Unit,
    viewModel: GalleryViewModel,
    hitTest: (Offset) -> Pair<FolderNode?, MediaItem?>?,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var pointerId = down.id
        // Path of items visited by this drag so far, oldest first -- see applyDragHover's own doc
        // comment for how it turns "hover back onto an earlier item" into "un-select everything
        // since then". Seeded (along with the pre-existing-selection snapshot) the first time
        // isDragSelecting is observed true, i.e. right after the long-press that started this
        // drag already selected the anchor tile.
        var dragStarted = false
        val dragPath = mutableListOf<DragItemKey>()
        var preExistingFolders = emptySet<String>()
        var preExistingMedia = emptySet<Long>()
        while (true) {
            // Read (without consuming, unless already drag-selecting) on the Initial pass --
            // parent-to-child, i.e. before each tile's own combinedClickable (a descendant) sees
            // this event on its default Main pass. That ordering is what lets us extend the
            // selection by consuming move events once a long-press has already won, without ever
            // needing to fight the grid/list's own scrollable for the tap itself.
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            if (!change.pressed) {
                if (isDragSelecting()) change.consume()
                setDragSelecting(false)
                break
            }
            if (isDragSelecting()) {
                if (!dragStarted) {
                    dragStarted = true
                    preExistingFolders = viewModel.selectedFolderPaths.value
                    preExistingMedia = viewModel.selectedMediaIds.value
                    dragItemKeyOf(hitTest(change.position))?.let { dragPath.add(it) }
                }
                applyDragHover(hitTest(change.position), dragPath, preExistingFolders, preExistingMedia, viewModel)
                change.consume()
            }
            pointerId = change.id
        }
    }
}

/**
 * The mixed grid/list branch's equivalent of [itemIndexAt] + [folderOrMediaAt]: maps a drag/long-
 * press position to the folder/media item under it, working from each visible row's own key
 * rather than a flat index -- a row is either a single [FolderListRow]/[MediaListRow] (key
 * "folder:<path>"/"media:<id>") or a chunk of grid tiles packed into one Row (key
 * "folderRow:<path>|<path>..."/"mediaRow:<id>|<id>..."), so the horizontal position within the row
 * picks out which folder/media that chunk's touch actually landed on.
 */
private fun folderOrMediaAtListPosition(
    listState: LazyListState,
    position: Offset,
    boxWidthPx: Float,
    clearancePx: Float,
    folders: List<FolderNode>,
    media: List<MediaItem>,
): Pair<FolderNode?, MediaItem?>? {
    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull {
        position.y >= it.offset && position.y <= it.offset + it.size
    } ?: return null
    val key = info.key as? String ?: return null
    val contentWidthPx = (boxWidthPx - clearancePx).coerceAtLeast(1f)
    val fraction = (position.x / contentWidthPx).coerceIn(0f, 0.999f)
    return when {
        key.startsWith("folderRow:") -> {
            val paths = key.removePrefix("folderRow:").split("|")
            val idx = (fraction * paths.size).toInt().coerceIn(0, paths.size - 1)
            folders.firstOrNull { it.path == paths[idx] } to null
        }
        key.startsWith("folder:") -> folders.firstOrNull { it.path == key.removePrefix("folder:") } to null
        key.startsWith("mediaRow:") -> {
            val ids = key.removePrefix("mediaRow:").split("|").mapNotNull { it.toLongOrNull() }
            val idx = (fraction * ids.size).toInt().coerceIn(0, ids.size - 1)
            null to media.firstOrNull { it.id == ids.getOrNull(idx) }
        }
        key.startsWith("media:") -> null to media.firstOrNull { it.id == key.removePrefix("media:").toLongOrNull() }
        else -> null
    }
}

/** The sort order that actually applies at [path]: its own override if it has one, else the
 * nearest ancestor's override that was scoped to include subfolders, else the global default.
 * Internal (not private) so the Move/Copy destination picker can sort its own folder list the
 * exact same way the gallery itself would at that same path. */
internal fun effectiveFolderSort(
    path: String,
    globalDefault: FolderSortOrder,
    overrides: Map<String, FolderSortOverride>,
): FolderSortOrder {
    overrides[path]?.let { return it.order }
    var ancestor = path
    while (ancestor.isNotEmpty()) {
        ancestor = ancestor.substringBeforeLast('/', "")
        val override = overrides[ancestor]
        if (override != null && override.includeSubfolders) return override.order
    }
    return globalDefault
}

internal fun sortedFolders(folders: List<FolderNode>, order: FolderSortOrder, includedFolders: Set<String>): List<FolderNode> =
    when (order) {
        FolderSortOrder.NAME_ASC -> folders.sortedBy { it.name.lowercase() }
        FolderSortOrder.NAME_DESC -> folders.sortedByDescending { it.name.lowercase() }
        FolderSortOrder.DATE_DESC -> folders.sortedByDescending { it.latestModifiedSec() }
        FolderSortOrder.DATE_ASC -> folders.sortedBy { it.latestModifiedSec() }
        FolderSortOrder.COUNT_DESC -> folders.sortedByDescending { it.promotionAwareItemCount(includedFolders) }
        FolderSortOrder.COUNT_ASC -> folders.sortedBy { it.promotionAwareItemCount(includedFolders) }
    }

/** The same sort order a folder's subfolders are sorted by, applied to its own media items too --
 * internal (like [sortedFolders]/[effectiveFolderSort]) so [MediaViewerScreen] and the photo/video
 * editors can independently re-derive the exact same order an index was picked from here, rather
 * than each hardcoding its own. [FolderSortOrder.COUNT_ASC]/[FolderSortOrder.COUNT_DESC] have no
 * per-item analog (there's no "count" on a single photo) so they fall back to newest-first, same
 * as the app's own long-standing default for media. */
internal fun sortedMedia(media: List<MediaItem>, order: FolderSortOrder): List<MediaItem> =
    when (order) {
        FolderSortOrder.NAME_ASC -> media.sortedBy { it.displayName.lowercase() }
        FolderSortOrder.NAME_DESC -> media.sortedByDescending { it.displayName.lowercase() }
        FolderSortOrder.DATE_DESC, FolderSortOrder.COUNT_DESC -> media.sortedByDescending { it.dateModifiedSec }
        FolderSortOrder.DATE_ASC, FolderSortOrder.COUNT_ASC -> media.sortedBy { it.dateModifiedSec }
    }

/**
 * Splits already-ordered [media] into consecutive runs sharing the same label under [setting]'s
 * criterion (e.g. "September 2026" for [GroupCriterion.LAST_MODIFIED_MONTHLY], "Videos"/"Photos"
 * for [GroupCriterion.FILE_TYPE]) -- a run breaks the moment the label changes, so date grouping
 * only forms one run per period when [media] is already date-sorted (as it usually is); this never
 * re-sorts the items themselves, only regroups them, so a folder sorted by something else can
 * still show correctly-labeled runs, just possibly more of them if the label isn't already
 * contiguous. [GroupCriterion.NONE] returns no groups at all (callers check that first via
 * whatever boolean they derive from the setting, same as the old dateDividersEnabled). Runs are
 * reversed as a whole (not the items inside each) when [FolderGroupSetting.ascending] is false.
 */
internal fun groupMedia(media: List<MediaItem>, setting: FolderGroupSetting): List<Pair<String, List<MediaItem>>> {
    if (media.isEmpty() || setting.criterion == GroupCriterion.NONE) return emptyList()
    // Each branch's lambda is assigned to a named local first, then returned as that plain
    // identifier -- NOT returned directly as the block's last expression -- because a `{ ... }`
    // lambda literal immediately following a call expression (SimpleDateFormat(...) here) on the
    // very next line is parsed by Kotlin as a TRAILING LAMBDA ARGUMENT to that call, not as its
    // own separate statement, however clear the intent looks with a line break in between. That
    // silently turned "val formatter = SimpleDateFormat(...)" plus a following lambda literal
    // into an attempt to call SimpleDateFormat's constructor WITH a trailing lambda parameter (a
    // constructor overload that doesn't exist), which is what actually failed to compile.
    // Referencing the lambda by name as the last statement sidesteps the ambiguity entirely.
    val labelOf: (MediaItem) -> String = when (setting.criterion) {
        GroupCriterion.LAST_MODIFIED_DAILY -> {
            val formatter = java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.getDefault())
            val label: (MediaItem) -> String = { item -> formatter.format(java.util.Date(item.dateModifiedSec * 1000)) }
            label
        }
        GroupCriterion.LAST_MODIFIED_MONTHLY -> {
            val formatter = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())
            val label: (MediaItem) -> String = { item -> formatter.format(java.util.Date(item.dateModifiedSec * 1000)) }
            label
        }
        GroupCriterion.FILE_TYPE -> { item -> if (item.isVideo) "Videos" else "Photos" }
        GroupCriterion.EXTENSION -> {
            { item -> item.displayName.substringAfterLast('.', "").uppercase().ifEmpty { "No extension" } }
        }
        GroupCriterion.NONE -> { _ -> "" }
    }
    val groups = mutableListOf<Pair<String, MutableList<MediaItem>>>()
    for (item in media) {
        val label = labelOf(item)
        val lastGroup = groups.lastOrNull()
        if (lastGroup != null && lastGroup.first == label) {
            lastGroup.second.add(item)
        } else {
            groups.add(label to mutableListOf(item))
        }
    }
    return if (setting.ascending) groups else groups.asReversed()
}

/** The small "September 2026"-style label + line the "group by month/year" folder option draws
 * between each date group. */
@Composable
private fun DateDividerLabel(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

private fun breadcrumbTitle(path: String): String {
    val realPath = path.removeSuffix("/.")
    return if (realPath.isEmpty()) "Gallery" else realPath.substringAfterLast('/')
}

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

/** The criterion half of a [FolderSortOrder] -- e.g. both [FolderSortOrder.NAME_ASC] and
 * [FolderSortOrder.NAME_DESC] are [NAME], just with a different [FolderSortOrder.isAscending].
 * Splitting the combined enum into criterion + direction for the dialog (see [SortDialog]) is what
 * lets it show them as two separate, independent radio groups -- a criterion list of 3 plus an
 * ascending/descending pair -- instead of one flat list of 6 combined options like "Name (A-Z)"
 * that made changing only the direction for the same criterion mean re-scanning the whole list for
 * the right combined entry. */
private enum class SortCriterionUi(val label: String) {
    NAME("Name"),
    DATE_MODIFIED("Last modified"),
    ITEM_COUNT("Item count"),
}

private fun FolderSortOrder.criterionUi(): SortCriterionUi = when (this) {
    FolderSortOrder.NAME_ASC, FolderSortOrder.NAME_DESC -> SortCriterionUi.NAME
    FolderSortOrder.DATE_ASC, FolderSortOrder.DATE_DESC -> SortCriterionUi.DATE_MODIFIED
    FolderSortOrder.COUNT_ASC, FolderSortOrder.COUNT_DESC -> SortCriterionUi.ITEM_COUNT
}

private fun FolderSortOrder.isAscending(): Boolean =
    this == FolderSortOrder.NAME_ASC || this == FolderSortOrder.DATE_ASC || this == FolderSortOrder.COUNT_ASC

private fun sortOrderOf(criterion: SortCriterionUi, ascending: Boolean): FolderSortOrder = when (criterion) {
    SortCriterionUi.NAME -> if (ascending) FolderSortOrder.NAME_ASC else FolderSortOrder.NAME_DESC
    SortCriterionUi.DATE_MODIFIED -> if (ascending) FolderSortOrder.DATE_ASC else FolderSortOrder.DATE_DESC
    SortCriterionUi.ITEM_COUNT -> if (ascending) FolderSortOrder.COUNT_ASC else FolderSortOrder.COUNT_DESC
}

/** Picking an order doesn't apply it immediately -- it first asks which folders it should apply
 * to (matching [SortScope]), since a folder's sort can now be scoped rather than always global.
 * That second step (and [SortScope] itself) is unchanged from before; only this first step's
 * layout is new, to match a criterion-radios + direction-radios + Cancel/OK dialog shape rather
 * than the old flat list of 6 combined options. "Path"/"Size"/"Random"/"Custom" from the reference
 * layout aren't included yet -- each needs real underlying data or behavior this app doesn't have
 * yet (recursive folder size, a persisted shuffle, or a drag-to-reorder UI) rather than being
 * faked as a criterion that wouldn't actually do anything. */
@Composable
private fun SortDialog(
    current: FolderSortOrder,
    folderName: String,
    onSelect: (FolderSortOrder, SortScope) -> Unit,
    onDismiss: () -> Unit,
) {
    var criterion by remember { mutableStateOf(current.criterionUi()) }
    var ascending by remember { mutableStateOf(current.isAscending()) }
    var showScopeStep by remember { mutableStateOf(false) }

    if (!showScopeStep) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Sort by") },
            text = {
                Column {
                    SortCriterionUi.entries.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { criterion = entry }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = entry == criterion, onClick = { criterion = entry })
                            Text(entry.label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SortDirectionOption("Ascending", selected = ascending) { ascending = true }
                    SortDirectionOption("Descending", selected = !ascending) { ascending = false }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScopeStep = true }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
        )
    } else {
        val order = sortOrderOf(criterion, ascending)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Apply to") },
            text = {
                Column {
                    Text(
                        "Sort by \"${order.label}\" for:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    SortScopeOption("All folders") { onSelect(order, SortScope.ALL); onDismiss() }
                    SortScopeOption("Just \"$folderName\"") { onSelect(order, SortScope.THIS_FOLDER); onDismiss() }
                    SortScopeOption("\"$folderName\" and its subfolders") {
                        onSelect(order, SortScope.THIS_FOLDER_AND_SUBFOLDERS)
                        onDismiss()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScopeStep = false }) { Text("Back") }
            },
        )
    }
}

@Composable
private fun SortScopeOption(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
    }
}

/** A single "<Label>" radio row, shared by [SortDialog] and [GroupByDialog] for their
 * ascending/descending pair. */
@Composable
private fun SortDirectionOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * "Group by", opened from a folder's own three-dot menu -- replaces what used to be a single
 * on/off "Group by month/year" toggle with a full dialog offering multiple grouping criteria (see
 * [GroupCriterion]), laid out the same way as [SortDialog]'s redesigned first step: criterion
 * radios, then an ascending/descending pair once a real criterion is picked (meaningless, so
 * hidden, for [GroupCriterion.NONE]). Applies only to this exact folder -- unlike sort, grouping
 * has no "all folders"/"subfolders" scope, so there's no second step here. "Date taken" from the
 * reference layout isn't included yet since the scanner doesn't capture MediaStore's DATE_TAKEN
 * column separately from last-modified yet (see [GroupCriterion]'s own doc comment).
 */
@Composable
private fun GroupByDialog(
    current: FolderGroupSetting,
    onConfirm: (FolderGroupSetting) -> Unit,
    onDismiss: () -> Unit,
) {
    var criterion by remember { mutableStateOf(current.criterion) }
    var ascending by remember { mutableStateOf(current.ascending) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group by") },
        text = {
            Column {
                GroupCriterion.entries.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { criterion = entry }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = entry == criterion, onClick = { criterion = entry })
                        Text(entry.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (criterion != GroupCriterion.NONE) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    SortDirectionOption("Ascending", selected = ascending) { ascending = true }
                    SortDirectionOption("Descending", selected = !ascending) { ascending = false }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(FolderGroupSetting(criterion, ascending)) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FolderCoverContent(folder: FolderNode, cover: FolderCover?, includedFolders: Set<String>) {
    if (cover is FolderCover.Text) {
        Box(Modifier.fillMaxSize().background(Color(cover.colorSeed)), contentAlignment = Alignment.Center) {
            // A 5%-of-the-tile margin on every side, however big a size the user picks -- the text
            // is free to start as large as they want, but never allowed to render closer to the
            // edge than this, since shrink-to-fit below clamps within these bounds. This inner box
            // (rather than sizing CoverText's own Text node to 90%x90% directly) is what actually
            // centers the text vertically too: a Text node forced to fill a tall box draws its
            // glyphs from the top of that box, not the middle, so the text needs its OWN wrap-sized
            // bounds centered by a parent -- this box is that parent, at the 90%x90% boundary.
            Box(Modifier.fillMaxSize(0.9f), contentAlignment = Alignment.Center) {
                CoverText(
                    text = cover.text,
                    modifier = Modifier.fillMaxWidth(),
                    userScale = cover.sizeScale,
                    bold = cover.bold,
                    wrap = cover.wrapText,
                    textColor = contrastingTextColor(cover.colorSeed),
                )
            }
        }
        return
    }
    val coverUri = if (cover is FolderCover.Photo) android.net.Uri.parse(cover.uri) else folder.promotionAwareCoverUri(includedFolders)
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

/** Font size at [userScale] 1f, as a fraction of [CoverText]'s own measured container width. Using
 * a size relative to the container (rather than a fixed sp value from Material typography) is
 * what makes the exact same [userScale] look the same relative size everywhere CoverText is used
 * -- a big cover-dialog preview and a small grid tile included -- instead of comfortably fitting
 * in one and being forced tiny (or wrapping badly) in the other. */
private const val COVER_FONT_WIDTH_FRACTION = 0.18f

/** Absolute floor purely to stop the shrink loop from ever reaching zero/negative size -- not a
 * "give up and truncate" threshold. Real folder names fit comfortably in [COVER_MAX_LINES] lines
 * well above this size; it only matters for pathologically long text, and even then the text
 * keeps wrapping to more lines (see [COVER_MAX_LINES]) rather than being cut off. */
private const val COVER_FONT_MIN_SIZE_SP = 5f
private const val COVER_FONT_SHRINK_STEP = 0.92f

/** How many lines a wrapped cover label is allowed to grow to before the (very rare) absolute
 * size floor takes over -- generous enough that ordinary folder names never need to truncate. */
private const val COVER_MAX_LINES_WRAP = 6

/** Verdana itself can't be bundled (proprietary, not redistributable); DejaVu Sans is the
 * long-standing free substitute for it (same look/metrics family), bundled as a font resource. */
private val CoverFontFamily = FontFamily(Font(R.font.cover_text_font))

private val CoverTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.6f),
    offset = Offset(2f, 3f),
    blurRadius = 6f,
)

/** True if wrapping [text] at [result]'s current font size split a word across two lines (e.g.
 * "Telecom" rendering as "Teleco" / "m") rather than breaking at a space -- Compose's default line
 * breaking still does this for a word wider than the container, and it doesn't count as
 * [TextLayoutResult.didOverflowWidth]/[TextLayoutResult.didOverflowHeight] since each line
 * technically fits. Treating it as its own "needs shrink" signal is what makes the font keep
 * shrinking until the whole word fits on one line instead of settling for an ugly mid-word split. */
private fun hasMidWordBreak(result: TextLayoutResult, text: String): Boolean {
    for (line in 0 until result.lineCount - 1) {
        val end = result.getLineEnd(line, visibleEnd = true)
        if (end <= 0 || end >= text.length) continue
        if (!text[end - 1].isWhitespace() && !text[end].isWhitespace()) return true
    }
    return false
}

/** A text folder cover's label, starting at [userScale] (the size the user picked in the cover
 * dialog) and shrinking further step by step until it fits within its own measured bounds -- both
 * width AND height -- so a long or large custom cover name never spills past the thumbnail it's
 * drawn on, however small the tile or row is. When [wrap] is on, a name too long to fit even at
 * the smallest readable size is allowed to grow past 2 lines (up to [COVER_MAX_LINES_WRAP])
 * instead of being cut off; only [wrap] off (single line by design) still ellipsizes if a name
 * can't be shrunk to fit on one line.
 *
 * The right size is computed in one deterministic pass with [TextMeasurer] before anything is
 * drawn, rather than the previous approach of rendering at a guess and reactively shrinking one
 * step per recomposition in response to [Text]'s own onTextLayout -- that iterative version could
 * visibly settle on a still-wrapping size (e.g. "Devonics Task" breaking mid-word into "Devoni" /
 * "cs Task") if the multi-frame convergence it depended on didn't fully play out. Measuring every
 * candidate size up front and picking the best BEFORE the first frame renders can't get stuck
 * partway like that. [clipToBounds] is still kept on the rendered text as a last-resort safety
 * net -- so even if a measurement is ever slightly off, the result is a hard clip within the
 * cover's own bounds, never text spilling out over whatever is drawn next to it. */
@Composable
internal fun CoverText(
    text: String,
    modifier: Modifier = Modifier,
    userScale: Float = 1f,
    bold: Boolean = false,
    wrap: Boolean = false,
    textColor: Color = Color.White,
) {
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        val maxLines = if (wrap) COVER_MAX_LINES_WRAP else 1
        val density = LocalDensity.current
        val constraints = Constraints(
            maxWidth = with(density) { maxWidth.roundToPx() },
            maxHeight = with(density) { maxHeight.roundToPx() },
        )
        val resolvedFontSizeSp = remember(text, userScale, bold, wrap, maxWidth, maxHeight) {
            val baseFontSizeSp = maxWidth.value * COVER_FONT_WIDTH_FRACTION * userScale
            var fontSizeSp = baseFontSizeSp
            while (true) {
                val result = textMeasurer.measure(
                    text = text,
                    style = TextStyle(
                        fontSize = fontSizeSp.sp,
                        fontWeight = fontWeight,
                        fontFamily = CoverFontFamily,
                        textAlign = TextAlign.Center,
                    ),
                    constraints = constraints,
                    maxLines = maxLines,
                )
                val needsShrink = result.didOverflowWidth ||
                    result.didOverflowHeight ||
                    (wrap && hasMidWordBreak(result, text))
                if (!needsShrink || fontSizeSp <= COVER_FONT_MIN_SIZE_SP) break
                fontSizeSp = (fontSizeSp * COVER_FONT_SHRINK_STEP).coerceAtLeast(COVER_FONT_MIN_SIZE_SP)
            }
            fontSizeSp
        }
        Text(
            text = text,
            color = textColor,
            style = TextStyle(
                fontSize = resolvedFontSizeSp.sp,
                fontWeight = fontWeight,
                fontFamily = CoverFontFamily,
                textAlign = TextAlign.Center,
                shadow = CoverTextShadow,
            ),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clipToBounds(),
        )
    }
}

/** White or black, whichever reads more clearly over a solid [seed] background -- so a cover text
 * color that's fixed to white doesn't go invisible the moment someone picks a light/white
 * background color. Uses perceived (not just averaged) luminance, weighting green highest since
 * the eye is most sensitive to it. */
internal fun contrastingTextColor(seed: Long): Color {
    val color = Color(seed)
    val luminance = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return if (luminance > 0.6f) Color.Black else Color.White
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
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        Modifier.background(
            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick) else Modifier,
                )
                .alpha(if (isHidden) 0.5f else 1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(thumbnailSizeDp.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        (cover as? FolderCover.Text)?.let { Color(it.colorSeed) } ?: MaterialTheme.colorScheme.surfaceVariant,
                    ),
            ) {
                when {
                    cover is FolderCover.Text -> Box(
                        Modifier.align(Alignment.Center).fillMaxSize(0.9f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CoverText(
                            text = cover.text,
                            modifier = Modifier.fillMaxWidth(),
                            userScale = cover.sizeScale,
                            bold = cover.bold,
                            wrap = cover.wrapText,
                            textColor = contrastingTextColor(cover.colorSeed),
                        )
                    }
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
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    GalleryListItem(
        thumbnailModel = if (cover is FolderCover.Photo) {
            android.net.Uri.parse(cover.uri)
        } else {
            folder.promotionAwareCoverUri(includedFolders)
        },
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
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
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
