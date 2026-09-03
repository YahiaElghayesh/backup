package com.elghayesh.gallerybackup.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elghayesh.gallerybackup.data.media.findNode
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel

/**
 * A file-explorer-style browser for choosing which folders to back up -- the same navigable
 * pattern as [com.elghayesh.gallerybackup.ui.gallery.FolderVisibilityExplorerScreen], but each
 * folder's checkbox is independent: checking a folder backs up just that folder's own items, it
 * does not automatically pull in its subfolders (matching [BackupViewModel.toggleFolder]'s
 * existing no-cascade behavior).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupFolderExplorerScreen(
    galleryViewModel: GalleryViewModel,
    backupViewModel: BackupViewModel,
    onBack: () -> Unit,
) {
    val root by galleryViewModel.root.collectAsState()
    val isLoading by galleryViewModel.isLoading.collectAsState()
    val selectedFolders by backupViewModel.selectedFolders.collectAsState(initial = emptySet())
    var currentPath by remember { mutableStateOf("") }

    val node = root?.findNode(currentPath)
    val children = node?.children?.values?.sortedBy { it.name.lowercase() } ?: emptyList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (currentPath.isEmpty()) "Folders to back up" else currentPath.substringAfterLast('/')) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (currentPath.isEmpty()) {
                                onBack()
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
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { galleryViewModel.refresh() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (currentPath.isEmpty()) {
                    Text(
                        "Check a folder to back it up. Each folder is independent -- checking one " +
                            "does not automatically include its subfolders, so open it and check " +
                            "those separately if you want them too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                if (children.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No subfolders here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(children, key = { it.path }) { folder ->
                            val checked = folder.path in selectedFolders
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { currentPath = folder.path }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { selected -> backupViewModel.toggleFolder(folder.path, selected) },
                                )
                                Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${folder.items.size} items directly inside",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(Icons.Filled.ChevronRight, contentDescription = "Open", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
