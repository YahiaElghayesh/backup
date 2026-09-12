package com.elghayesh.gallerybackup.ui.trash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
) {
    val trashedItems by viewModel.trashedItems.collectAsState()
    val trashedEntries by viewModel.trashedEntries.collectAsState()
    val retentionDays by viewModel.trashRetentionDays.collectAsState()
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var confirmPermanentDelete by remember { mutableStateOf(false) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    // Drag-to-select state, mirroring the main gallery grid's own dragSelectGesture: a long-press
    // seeds [dragPath] with the pressed tile and snapshots whatever was already selected into
    // [preExistingSelection], then every tile the finger moves over while still down gets appended
    // to (or, if it's re-hovering an earlier tile in this same drag, truncates) that path -- see
    // [applyTrashDragHover]'s own doc comment for why hovering backward deselects.
    val gridState = rememberLazyGridState()
    var isDragSelecting by remember { mutableStateOf(false) }
    val dragPath = remember { mutableStateOf(listOf<Long>()) }
    var preExistingSelection by remember { mutableStateOf(setOf<Long>()) }

    // Back (system button or gesture) exits selection instead of leaving the screen entirely --
    // the on-screen arrow already did this via its own onClick, but that's a separate code path
    // from the hardware/gesture back button, which otherwise falls straight through to onBack().
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptySet() }

    LaunchedEffect(Unit) { viewModel.purgeExpiredTrash() }

    val selectedItems = trashedItems.filter { it.id in selectedIds }

    if (confirmPermanentDelete) {
        AlertDialog(
            onDismissRequest = { confirmPermanentDelete = false },
            title = { Text("Delete forever?") },
            text = { Text("${selectedItems.size} item(s) will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMediaItems(selectedItems, skipTrash = true)
                    selectedIds = emptySet()
                    confirmPermanentDelete = false
                }) { Text("Delete forever") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPermanentDelete = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmEmptyTrash) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text("Empty recycle bin?") },
            text = { Text("All ${trashedItems.size} item(s) in the trash will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.emptyTrash()
                    selectedIds = emptySet()
                    confirmEmptyTrash = false
                }) { Text("Empty") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmptyTrash = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedIds.isEmpty()) "Trash" else "${selectedIds.size} / ${trashedItems.size} selected") },
                navigationIcon = {
                    IconButton(onClick = { if (selectedIds.isNotEmpty()) selectedIds = emptySet() else onBack() }) {
                        Icon(
                            if (selectedIds.isNotEmpty()) Icons.Filled.Close else Icons.Filled.ArrowBack,
                            contentDescription = if (selectedIds.isNotEmpty()) "Cancel selection" else "Back",
                        )
                    }
                },
                actions = {
                    if (selectedIds.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                selectedIds = if (selectedIds.size == trashedItems.size) {
                                    emptySet()
                                } else {
                                    trashedItems.map { it.id }.toSet()
                                }
                            },
                        ) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                        }
                    } else if (trashedItems.isNotEmpty()) {
                        IconButton(onClick = { confirmEmptyTrash = true }) {
                            Icon(Icons.Filled.DeleteForever, contentDescription = "Empty recycle bin")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selectedIds.isNotEmpty()) {
                BottomAppBar {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        TextButton(onClick = {
                            viewModel.restoreFromTrash(selectedItems.map { it.id })
                            selectedIds = emptySet()
                        }) { Text("Restore") }
                        TextButton(onClick = { confirmPermanentDelete = true }) { Text("Delete forever") }
                    }
                }
            }
        },
    ) { padding ->
        if (trashedItems.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Trash is empty.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                Text(
                    "Items are automatically deleted forever after $retentionDays days.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize().pointerInput(trashedItems.map { it.id }) {
                        trashDragSelectGesture(
                            gridState = gridState,
                            isDragSelecting = { isDragSelecting },
                            setDragSelecting = { isDragSelecting = it },
                            onHover = { hoveredId ->
                                applyTrashDragHover(
                                    hoveredId = hoveredId,
                                    dragPath = dragPath.value,
                                    setDragPath = { dragPath.value = it },
                                    preExisting = preExistingSelection,
                                    setSelectedIds = { selectedIds = it },
                                )
                            },
                        )
                    },
                ) {
                    items(trashedItems, key = { it.id }) { item ->
                        val trashedAt = trashedEntries[item.id] ?: 0L
                        val nowSec = System.currentTimeMillis() / 1000
                        val daysLeft = (retentionDays - (nowSec - trashedAt) / 86400).coerceAtLeast(0)
                        TrashTile(
                            item = item,
                            daysLeft = daysLeft,
                            isSelected = item.id in selectedIds,
                            onClick = {
                                selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
                            },
                            onLongClick = {
                                preExistingSelection = selectedIds
                                dragPath.value = listOf(item.id)
                                selectedIds = selectedIds + item.id
                                isDragSelecting = true
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Maps a drag position to the trashed item's id under it, reading straight off the grid's own
 * visible-item layout so the hit test always matches whatever the grid is actually showing. */
private fun trashItemIdAt(gridState: LazyGridState, position: Offset): Long? {
    val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { itemInfo ->
        position.x >= itemInfo.offset.x && position.x <= itemInfo.offset.x + itemInfo.size.width &&
            position.y >= itemInfo.offset.y && position.y <= itemInfo.offset.y + itemInfo.size.height
    }
    return info?.key as? Long
}

/**
 * The trash grid's own equivalent of the main gallery's dragSelectGesture -- a long-press (via
 * each [TrashTile]'s onLongClick) starts drag-selection, and while the same finger stays down and
 * moves, every tile it passes over gets folded into the selection via [onHover]. Attached directly
 * to the [LazyVerticalGrid]'s own modifier chain (not a separate ancestor Box) so hit positions are
 * always in the same coordinate frame [gridState]'s own layoutInfo reports them in.
 */
private suspend fun PointerInputScope.trashDragSelectGesture(
    gridState: LazyGridState,
    isDragSelecting: () -> Boolean,
    setDragSelecting: (Boolean) -> Unit,
    onHover: (Long?) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var pointerId = down.id
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            if (!change.pressed) {
                if (isDragSelecting()) change.consume()
                setDragSelecting(false)
                break
            }
            if (isDragSelecting()) {
                onHover(trashItemIdAt(gridState, change.position))
                change.consume()
            }
            pointerId = change.id
        }
    }
}

/**
 * Folds a newly-hovered tile into the current drag's path. Hovering onto a tile not yet in
 * [dragPath] extends it (selecting one more item); hovering BACK onto a tile already in the path
 * truncates the path down to (and including) that tile, un-selecting everything visited since --
 * lets a user overshoot during a drag and back off without lifting their finger, matching the
 * main gallery grid's own drag-select behavior.
 */
private fun applyTrashDragHover(
    hoveredId: Long?,
    dragPath: List<Long>,
    setDragPath: (List<Long>) -> Unit,
    preExisting: Set<Long>,
    setSelectedIds: (Set<Long>) -> Unit,
) {
    if (hoveredId == null) return
    val existingIndex = dragPath.indexOf(hoveredId)
    val newPath = if (existingIndex >= 0) dragPath.subList(0, existingIndex + 1) else dragPath + hoveredId
    setDragPath(newPath)
    setSelectedIds(preExisting + newPath)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashTile(
    item: MediaItem,
    daysLeft: Long,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isSelected) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            )
        }
        Text(
            text = "${daysLeft}d left",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .padding(4.dp),
        )
    }
}
