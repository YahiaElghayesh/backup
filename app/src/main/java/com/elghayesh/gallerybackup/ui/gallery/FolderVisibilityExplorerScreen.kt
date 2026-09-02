package com.elghayesh.gallerybackup.ui.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.elghayesh.gallerybackup.data.media.findNode

/**
 * A file-explorer-style browser with two independent checkboxes per folder:
 * - **Show**: pins this folder (with everything inside it, unfiltered) as its own tile on the
 *   gallery's home page, in addition to wherever it already sits when you browse normally. Purely
 *   additive -- pinning a subfolder doesn't hide or change its parent, and vice versa.
 * - **Hide**: removes this folder (and everything inside it) from the gallery everywhere, until a
 *   later "Unhide all" or unchecking it again. Independent of Show -- a folder can be pinned and
 *   hidden at once (hidden wins), or neither, or just one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderVisibilityExplorerScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
) {
    val root by viewModel.root.collectAsState()
    val includedFolders by viewModel.includedFolders.collectAsState()
    val hiddenFolders by viewModel.hiddenFolders.collectAsState()
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
                    TextButton(onClick = { viewModel.unhideAllFolders() }) {
                        Text("Unhide all")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (currentPath.isEmpty()) {
                Text(
                    "Show pins a folder (with everything inside it) to the gallery's home page, on " +
                        "top of what's already there. Hide removes a folder everywhere until you " +
                        "uncheck it or tap \"Unhide all\".",
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
                        val pinned = folder.path in includedFolders
                        val hidden = folder.path in hiddenFolders
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Checkbox(
                                    checked = pinned,
                                    onCheckedChange = { checked -> viewModel.setFolderIncluded(folder.path, checked) },
                                )
                                Text("Show", style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Checkbox(
                                    checked = hidden,
                                    onCheckedChange = { checked -> viewModel.setFolderHidden(folder.path, checked) },
                                )
                                Text("Hide", style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(4.dp))
                            Row(
                                Modifier
                                    .weight(1f)
                                    .clickable { currentPath = folder.path },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        buildString {
                                            append("${folder.totalItemCount()} items")
                                            if (pinned) append(" · pinned to home")
                                            if (hidden) append(" · hidden")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = "Open",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
