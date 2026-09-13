package com.elghayesh.gallerybackup.ui.gallery

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.elghayesh.gallerybackup.data.media.withVirtualFolders
import com.elghayesh.gallerybackup.ui.common.AllFilesAccessPrompt

/**
 * A file-explorer-style browser with two independent checkboxes per folder:
 * - **Show**: pins this folder (with everything inside it, unfiltered once you open it) as its
 *   own tile on the gallery's home page. This *promotes* it out of its real parent's own listing
 *   -- e.g. pinning "gg/bb" makes "bb" show up next to "gg" at the gallery's root, and opening
 *   "gg" from then on shows everything except "bb". Pinning a folder doesn't change whether its
 *   own parent or children are pinned.
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
    val rawRoot by viewModel.root.collectAsState()
    val virtualFolders by viewModel.virtualFolders.collectAsState()
    val allDeviceFolderPaths by viewModel.allDeviceFolderPaths.collectAsState()
    val hasLoadedAllDeviceFolders by viewModel.hasLoadedAllDeviceFolders.collectAsState()
    val includedFolders by viewModel.includedFolders.collectAsState()
    val hiddenFolders by viewModel.hiddenFolders.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var currentPath by remember { mutableStateOf("") }

    // Unconditional, unlike AllFilesAccessPrompt's onGranted below -- without that permission the
    // walk just comes back with whatever it can see (typically nothing), but it still needs to run
    // once so hasLoadedAllDeviceFolders flips true and the loading spinner below doesn't spin forever.
    LaunchedEffect(Unit) { viewModel.refreshAllDeviceFolders() }

    val root = rawRoot?.withVirtualFolders(virtualFolders + allDeviceFolderPaths)
    val node = root?.findNode(currentPath)
    val children = node?.children?.values?.sortedBy { it.name.lowercase() } ?: emptyList()

    BackHandler(enabled = currentPath.isNotEmpty()) {
        currentPath = currentPath.substringBeforeLast('/', "")
    }

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
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = {
                viewModel.refresh()
                viewModel.refreshAllDeviceFolders()
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                AllFilesAccessPrompt(onGranted = { viewModel.refreshAllDeviceFolders() })
                if (currentPath.isEmpty()) {
                    Text(
                        "Show pins a folder to the gallery's home page and moves it out of its parent's " +
                            "listing there. Hide removes a folder everywhere until you uncheck it or tap " +
                            "\"Unhide all\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                if (!hasLoadedAllDeviceFolders) {
                    // Waits for the FULL device-wide folder walk to land before showing anything --
                    // otherwise this would first paint just the handful of folders MediaStore
                    // already knew about (the fast scan behind [root]), then visibly jump to the
                    // complete list a few seconds later once the slow walk finishes.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (children.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No subfolders here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(children, key = { it.path }) { folder ->
                            val pinned = folder.path in includedFolders
                            val hidden = folder.path in hiddenFolders
                            val pinnedInside = includedFolders.count {
                                it != folder.path && it.startsWith("${folder.path}/")
                            }
                            val hiddenInside = hiddenFolders.count {
                                it != folder.path && it.startsWith("${folder.path}/")
                            }
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
                                                if (pinnedInside > 0) append(" · $pinnedInside subfolder${if (pinnedInside == 1) "" else "s"} pinned")
                                                if (hiddenInside > 0) append(" · $hiddenInside subfolder${if (hiddenInside == 1) "" else "s"} hidden")
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
}
