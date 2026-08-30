package com.elghayesh.gallerybackup.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elghayesh.gallerybackup.data.media.FolderNode
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.data.media.findNode
import com.elghayesh.gallerybackup.data.media.latestModifiedSec
import com.elghayesh.gallerybackup.data.settings.FolderSortOrder
import com.elghayesh.gallerybackup.data.settings.ViewType
import com.elghayesh.gallerybackup.ui.common.FolderPickerDialog
import com.elghayesh.gallerybackup.ui.common.MediaActionBar
import com.elghayesh.gallerybackup.ui.common.PropertiesDialog
import com.elghayesh.gallerybackup.ui.common.rememberDeleteRequester
import com.elghayesh.gallerybackup.ui.common.shareMedia
import java.util.concurrent.TimeUnit

private enum class FolderTransferMode { MOVE, COPY }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    path: String,
    viewModel: GalleryViewModel,
    onOpenFolder: (String) -> Unit,
    onOpenMedia: (path: String, index: Int) -> Unit,
    onOpenGallerySettings: () -> Unit,
    onOpenBackupSettings: () -> Unit,
    onEditPhoto: (path: String, index: Int) -> Unit,
    onEditVideo: (path: String, index: Int) -> Unit,
    onNavigateUp: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.loadIfNeeded() }
    LaunchedEffect(path) { viewModel.clearSelection() }
    val context = LocalContext.current

    val visibleRoot by viewModel.visibleRoot.collectAsState()
    val rawRoot by viewModel.root.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val viewType by viewModel.viewType.collectAsState()
    val gridColumns by viewModel.gridColumns.collectAsState()
    val folderSort by viewModel.folderSort.collectAsState()
    val showHidden by viewModel.showHidden.collectAsState()
    val hiddenFolders by viewModel.hiddenFolders.collectAsState()
    val hiddenMediaIds by viewModel.hiddenMediaIds.collectAsState()
    val selectedMediaIds by viewModel.selectedMediaIds.collectAsState()
    val node = visibleRoot?.findNode(path)

    val folders = node?.let { sortedFolders(it.children.values.toList(), folderSort) } ?: emptyList()
    val media = node?.items?.sortedByDescending { it.dateModifiedSec } ?: emptyList()
    val selectedItems = media.filter { it.id in selectedMediaIds }
    val isSelectionMode = selectedMediaIds.isNotEmpty()

    var overflowExpanded by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var transferMode by remember { mutableStateOf<FolderTransferMode?>(null) }
    var showProperties by remember { mutableStateOf(false) }

    val requestDelete = rememberDeleteRequester(viewModel)

    if (showSortDialog) {
        SortDialog(current = folderSort, onSelect = { viewModel.setFolderSort(it) }, onDismiss = { showSortDialog = false })
    }
    transferMode?.let { mode ->
        FolderPickerDialog(
            root = rawRoot,
            title = if (mode == FolderTransferMode.MOVE) "Move to..." else "Copy to...",
            onPick = { destination ->
                if (mode == FolderTransferMode.MOVE) {
                    viewModel.moveMediaItems(selectedItems, destination)
                } else {
                    viewModel.copyMediaItems(selectedItems, destination)
                }
                transferMode = null
            },
            onDismiss = { transferMode = null },
        )
    }
    if (showProperties && selectedItems.isNotEmpty()) {
        PropertiesDialog(items = selectedItems, onDismiss = { showProperties = false })
    }

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedItems.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                        }
                    },
                    actions = {
                        val allSelectedHidden = selectedItems.isNotEmpty() && selectedItems.all { it.id in hiddenMediaIds }
                        MediaActionBar(
                            onEdit = if (selectedItems.size == 1) {
                                {
                                    val only = selectedItems.first()
                                    val index = media.indexOf(only)
                                    if (only.isVideo) onEditVideo(path, index) else onEditPhoto(path, index)
                                }
                            } else {
                                null
                            },
                            onShare = { shareMedia(context, selectedItems) },
                            onDelete = { requestDelete(selectedItems) },
                            onMoveTo = { transferMode = FolderTransferMode.MOVE },
                            onCopyTo = { transferMode = FolderTransferMode.COPY },
                            onProperties = { showProperties = true },
                            hideLabel = if (allSelectedHidden) "Unhide" else "Hide",
                            onToggleHidden = {
                                selectedItems.forEach { viewModel.setMediaHidden(it.id, !allSelectedHidden) }
                            },
                        )
                    },
                )
            } else {
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
                        IconButton(onClick = {
                            viewModel.setViewType(if (viewType == ViewType.GRID) ViewType.LIST else ViewType.GRID)
                        }) {
                            Icon(
                                if (viewType == ViewType.GRID) Icons.Filled.ViewList else Icons.Filled.GridView,
                                contentDescription = "Toggle view type",
                            )
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
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                isLoading && visibleRoot == null -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                node == null -> {
                    Text(
                        "No media found here.",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                node.totalItemCount() == 0 -> {
                    Text(
                        "No photos or videos yet.",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    if (viewType == ViewType.GRID) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(gridColumns),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            gridItems(folders, key = { "folder:${it.path}" }) { folder ->
                                FolderGridTile(
                                    folder = folder,
                                    isHidden = folder.path in hiddenFolders,
                                    onClick = { onOpenFolder(folder.path) },
                                    onToggleHidden = {
                                        viewModel.setFolderHidden(folder.path, folder.path !in hiddenFolders)
                                    },
                                    onExclude = { viewModel.setFolderExcluded(folder.path, true) },
                                )
                            }
                            gridItemsIndexed(media, key = { _, item -> "media:${item.id}" }) { index, item ->
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
                                    onLongClick = { viewModel.toggleMediaSelection(item.id) },
                                )
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(folders, key = { "folder:${it.path}" }) { folder ->
                                FolderListRow(
                                    folder = folder,
                                    isHidden = folder.path in hiddenFolders,
                                    onClick = { onOpenFolder(folder.path) },
                                    onToggleHidden = {
                                        viewModel.setFolderHidden(folder.path, folder.path !in hiddenFolders)
                                    },
                                    onExclude = { viewModel.setFolderExcluded(folder.path, true) },
                                )
                            }
                            itemsIndexed(media, key = { _, item -> "media:${item.id}" }) { index, item ->
                                MediaListRow(
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
                                    onLongClick = { viewModel.toggleMediaSelection(item.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sortedFolders(folders: List<FolderNode>, order: FolderSortOrder): List<FolderNode> =
    when (order) {
        FolderSortOrder.NAME_ASC -> folders.sortedBy { it.name.lowercase() }
        FolderSortOrder.NAME_DESC -> folders.sortedByDescending { it.name.lowercase() }
        FolderSortOrder.DATE_DESC -> folders.sortedByDescending { it.latestModifiedSec() }
        FolderSortOrder.DATE_ASC -> folders.sortedBy { it.latestModifiedSec() }
        FolderSortOrder.COUNT_DESC -> folders.sortedByDescending { it.totalItemCount() }
        FolderSortOrder.COUNT_ASC -> folders.sortedBy { it.totalItemCount() }
    }

private fun breadcrumbTitle(path: String): String =
    if (path.isEmpty()) "Gallery" else path.substringAfterLast('/')

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
private fun ColumnScope.FolderMenuItems(
    isHidden: Boolean,
    onToggleHidden: () -> Unit,
    onExclude: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenuItem(text = { Text(if (isHidden) "Unhide" else "Hide") }, onClick = { onToggleHidden(); onDismiss() })
    DropdownMenuItem(text = { Text("Exclude from gallery") }, onClick = { onExclude(); onDismiss() })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderGridTile(
    folder: FolderNode,
    isHidden: Boolean,
    onClick: () -> Unit,
    onToggleHidden: () -> Unit,
    onExclude: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
            .alpha(if (isHidden) 0.5f else 1f),
    ) {
        val cover = folder.coverUri()
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = folder.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
        } else {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                modifier = Modifier.align(Alignment.Center),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${folder.name}  (${folder.totalItemCount()})",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(6.dp),
        )
        if (isHidden) {
            Icon(
                Icons.Filled.VisibilityOff,
                contentDescription = "Hidden",
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            FolderMenuItems(isHidden, onToggleHidden, onExclude, onDismiss = { menuExpanded = false })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGridTile(
    item: MediaItem,
    isHidden: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .alpha(if (isHidden) 0.5f else 1f),
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.isVideo) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = "Video",
                tint = Color.White,
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
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            )
        }
        if (isSelected) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryListItem(
    thumbnailModel: Any?,
    icon: ImageVector?,
    title: String,
    subtitle: String,
    isHidden: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menuContent: (@Composable ColumnScope.(closeMenu: () -> Unit) -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(
        Modifier.background(
            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (menuContent != null) menuExpanded = true else onLongClick() },
                )
                .alpha(if (isHidden) 0.5f else 1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (thumbnailModel != null) {
                    AsyncImage(
                        model = thumbnailModel,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.align(Alignment.Center))
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
        if (menuContent != null) {
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                menuContent(closeMenu = { menuExpanded = false })
            }
        }
    }
}

@Composable
private fun FolderListRow(
    folder: FolderNode,
    isHidden: Boolean,
    onClick: () -> Unit,
    onToggleHidden: () -> Unit,
    onExclude: () -> Unit,
) {
    GalleryListItem(
        thumbnailModel = folder.coverUri(),
        icon = Icons.Filled.Folder,
        title = folder.name,
        subtitle = "${folder.totalItemCount()} items",
        isHidden = isHidden,
        isSelected = false,
        onClick = onClick,
        onLongClick = {},
        menuContent = { closeMenu -> FolderMenuItems(isHidden, onToggleHidden, onExclude, onDismiss = closeMenu) },
    )
}

@Composable
private fun MediaListRow(
    item: MediaItem,
    isHidden: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    GalleryListItem(
        thumbnailModel = item.uri,
        icon = null,
        title = item.displayName,
        subtitle = if (item.isVideo) "Video - ${formatDuration(item.durationMs)}" else "Photo",
        isHidden = isHidden,
        isSelected = isSelected,
        onClick = onClick,
        onLongClick = onLongClick,
        menuContent = null,
    )
}

private fun formatDuration(durationMs: Long): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(durationMs)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
