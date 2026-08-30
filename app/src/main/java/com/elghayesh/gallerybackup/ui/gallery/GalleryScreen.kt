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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elghayesh.gallerybackup.data.media.FolderNode
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.data.media.findNode
import com.elghayesh.gallerybackup.data.media.latestModifiedSec
import com.elghayesh.gallerybackup.data.settings.FolderSortOrder
import com.elghayesh.gallerybackup.data.settings.ViewType
import java.util.concurrent.TimeUnit

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

    val visibleRoot by viewModel.visibleRoot.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val viewType by viewModel.viewType.collectAsState()
    val gridColumns by viewModel.gridColumns.collectAsState()
    val folderSort by viewModel.folderSort.collectAsState()
    val showHidden by viewModel.showHidden.collectAsState()
    val hiddenFolders by viewModel.hiddenFolders.collectAsState()
    val hiddenMediaIds by viewModel.hiddenMediaIds.collectAsState()
    val node = visibleRoot?.findNode(path)

    var overflowExpanded by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }

    if (showSortDialog) {
        SortDialog(current = folderSort, onSelect = { viewModel.setFolderSort(it) }, onDismiss = { showSortDialog = false })
    }

    Scaffold(
        topBar = {
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
                    val folders = sortedFolders(node.children.values.toList(), folderSort)
                    val media = node.items.sortedByDescending { it.dateModifiedSec }

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
                                    onClick = { onOpenMedia(path, index) },
                                    onToggleHidden = {
                                        viewModel.setMediaHidden(item.id, item.id !in hiddenMediaIds)
                                    },
                                    onDelete = { viewModel.deleteMedia(item) },
                                    onEdit = { if (item.isVideo) onEditVideo(path, index) else onEditPhoto(path, index) },
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
                                    onClick = { onOpenMedia(path, index) },
                                    onToggleHidden = {
                                        viewModel.setMediaHidden(item.id, item.id !in hiddenMediaIds)
                                    },
                                    onDelete = { viewModel.deleteMedia(item) },
                                    onEdit = { if (item.isVideo) onEditVideo(path, index) else onEditPhoto(path, index) },
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

@Composable
private fun ColumnScope.MediaMenuItems(
    item: MediaItem,
    isHidden: Boolean,
    onToggleHidden: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenuItem(text = { Text(if (isHidden) "Unhide" else "Hide") }, onClick = { onToggleHidden(); onDismiss() })
    DropdownMenuItem(
        text = { Text(if (item.isVideo) "Trim video" else "Edit photo") },
        onClick = { onEdit(); onDismiss() },
    )
    DropdownMenuItem(text = { Text("Delete") }, onClick = { onDelete(); onDismiss() })
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
    onClick: () -> Unit,
    onToggleHidden: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = { menuExpanded = true })
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
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            MediaMenuItems(item, isHidden, onToggleHidden, onEdit, onDelete, onDismiss = { menuExpanded = false })
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    menuContent: @Composable ColumnScope.() -> Unit,
) {
    Box {
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
            if (isHidden) {
                Icon(Icons.Filled.VisibilityOff, contentDescription = "Hidden", modifier = Modifier.padding(start = 8.dp))
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu, content = menuContent)
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
    var menuExpanded by remember { mutableStateOf(false) }
    GalleryListItem(
        thumbnailModel = folder.coverUri(),
        icon = Icons.Filled.Folder,
        title = folder.name,
        subtitle = "${folder.totalItemCount()} items",
        isHidden = isHidden,
        onClick = onClick,
        onLongClick = { menuExpanded = true },
        menuExpanded = menuExpanded,
        onDismissMenu = { menuExpanded = false },
        menuContent = { FolderMenuItems(isHidden, onToggleHidden, onExclude, onDismiss = { menuExpanded = false }) },
    )
}

@Composable
private fun MediaListRow(
    item: MediaItem,
    isHidden: Boolean,
    onClick: () -> Unit,
    onToggleHidden: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    GalleryListItem(
        thumbnailModel = item.uri,
        icon = null,
        title = item.displayName,
        subtitle = if (item.isVideo) "Video - ${formatDuration(item.durationMs)}" else "Photo",
        isHidden = isHidden,
        onClick = onClick,
        onLongClick = { menuExpanded = true },
        menuExpanded = menuExpanded,
        onDismissMenu = { menuExpanded = false },
        menuContent = {
            MediaMenuItems(item, isHidden, onToggleHidden, onEdit, onDelete, onDismiss = { menuExpanded = false })
        },
    )
}

private fun formatDuration(durationMs: Long): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(durationMs)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
