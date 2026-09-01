package com.elghayesh.gallerybackup.ui.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.elghayesh.gallerybackup.data.media.FolderNode
import com.elghayesh.gallerybackup.data.media.findNode

/**
 * A real file-explorer-style browser for choosing which folders show up in the gallery -- the
 * opposite of the old flat "every folder, opt out" checklist. Marking a folder here makes it (and
 * everything under it) show up at the gallery's root; a subfolder inherits that automatically
 * unless you drill into it and mark it hidden specifically, which overrides the inherited choice
 * just for that branch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderVisibilityExplorerScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
) {
    val root by viewModel.root.collectAsState()
    val includedFolders by viewModel.includedFolders.collectAsState()
    val excludedFolders by viewModel.excludedFolders.collectAsState()
    var currentPath by remember { mutableStateOf("") }

    val node = root?.findNode(currentPath)
    val children = node?.children?.values?.sortedBy { it.name.lowercase() } ?: emptyList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (currentPath.isEmpty()) "Choose gallery folders" else currentPath.substringAfterLast('/')) },
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
                actions = {
                    TextButton(onClick = { viewModel.resetFolderVisibility() }) {
                        Text("Show all")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (currentPath.isEmpty()) {
                Text(
                    "Check a folder to show it (and everything inside it) in the gallery. " +
                        "Open a checked folder to uncheck specific subfolders you don't want.",
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
                        val visible = isEffectivelyVisible(folder.path, includedFolders, excludedFolders)
                        val hasOwnOverride = folder.path in includedFolders || folder.path in excludedFolders
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { currentPath = folder.path }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = visible,
                                onCheckedChange = { checked -> viewModel.setFolderVisibility(folder.path, checked) },
                            )
                            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (hasOwnOverride) "${folder.totalItemCount()} items" else "${folder.totalItemCount()} items (inherited)",
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

/** Mirrors [com.elghayesh.gallerybackup.data.media.effectiveExcludedFolders]'s inheritance walk for a single path. */
private fun isEffectivelyVisible(path: String, includedFolders: Set<String>, excludedFolders: Set<String>): Boolean {
    var visible = includedFolders.isEmpty()
    var built = ""
    for (segment in path.split("/").filter { it.isNotBlank() }) {
        built = if (built.isEmpty()) segment else "$built/$segment"
        visible = when {
            built in excludedFolders -> false
            built in includedFolders -> true
            else -> visible
        }
    }
    return visible
}
