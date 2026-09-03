package com.elghayesh.gallerybackup.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.elghayesh.gallerybackup.data.media.FolderNode
import com.elghayesh.gallerybackup.data.media.findNode

/**
 * A full-screen, navigable browser of the real folder tree for picking a Move to/Copy to
 * destination (or any other "pick a folder" flow). Laid out as the same photo-covered tile grid
 * as the gallery itself, not a bare file-explorer list, so a destination is recognizable by its
 * thumbnails rather than just a folder icon and a name -- browse into subfolders like the
 * gallery, or create a new folder right where you're standing and land inside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderTreePickerDialog(
    root: FolderNode?,
    title: String,
    onPick: (path: String) -> Unit,
    onCreateFolder: (parentPath: String, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentPath by remember { mutableStateOf("") }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    val node = root?.findNode(currentPath)
    val children = node?.children?.values?.sortedBy { it.name.lowercase() } ?: emptyList()

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onConfirm = { name ->
                onCreateFolder(currentPath, name)
                currentPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                showCreateFolderDialog = false
            },
            onDismiss = { showCreateFolderDialog = false },
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (currentPath.isEmpty()) title else currentPath.substringAfterLast('/')) },
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    if (currentPath.isEmpty()) {
                                        onDismiss()
                                    } else {
                                        currentPath = currentPath.substringBeforeLast('/', "")
                                    }
                                },
                            ) {
                                Icon(
                                    if (currentPath.isEmpty()) Icons.Filled.Close else Icons.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        },
                    )
                },
                bottomBar = {
                    BottomAppBar {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { showCreateFolderDialog = true }) { Text("New folder") }
                            Button(onClick = { onPick(currentPath) }) {
                                Text(if (currentPath.isEmpty()) "Select storage root" else "Select this folder")
                            }
                        }
                    }
                },
            ) { padding ->
                if (children.isEmpty()) {
                    Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No subfolders here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(padding.calculateTopPadding() + 8.dp, 8.dp, 8.dp, 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(children, key = { it.path }) { folder ->
                            PickerFolderTile(folder = folder, onClick = { currentPath = folder.path })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerFolderTile(folder: FolderNode, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .aspectRatio(1f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val cover = folder.coverUri()
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = folder.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            folder.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "${folder.totalItemCount()} items",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
