package com.elghayesh.gallerybackup.ui.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.graphics.Color
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedIds.isEmpty()) "Trash" else "${selectedIds.size} selected") },
                navigationIcon = {
                    IconButton(onClick = { if (selectedIds.isNotEmpty()) selectedIds = emptySet() else onBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
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
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
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
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashTile(item: MediaItem, daysLeft: Long, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onClick),
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
