package com.elghayesh.gallerybackup.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.elghayesh.gallerybackup.data.media.FolderNode
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.data.media.findNode
import com.elghayesh.gallerybackup.data.media.searchFolders
import com.elghayesh.gallerybackup.data.media.searchMedia

/**
 * A full-screen search UI over the whole (already-visible, i.e. hidden/trash-filtered) folder
 * tree, matching folder names and media file names anywhere in the tree -- not just the folder
 * currently being browsed. Reuses the same FolderListRow/MediaListRow tiles the gallery's own list
 * view uses, for the same reason FolderTreePickerDialog does: a result should look exactly like
 * the row it's a match for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchDialog(
    viewModel: GalleryViewModel,
    root: FolderNode?,
    onOpenFolder: (path: String) -> Unit,
    onOpenMedia: (path: String, index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val includedFolders by viewModel.includedFolders.collectAsState()
    val folderSort by viewModel.folderSort.collectAsState()
    val folderSortOverrides by viewModel.folderSortOverrides.collectAsState()
    val folderRowSize by viewModel.folderRowSize.collectAsState()
    val folderThumbnailWidth by viewModel.folderThumbnailWidth.collectAsState()
    val mediaRowSize by viewModel.mediaRowSize.collectAsState()
    val mediaThumbnailWidth by viewModel.mediaThumbnailWidth.collectAsState()

    val trimmedQuery = query.trim()
    val folderResults = remember(root, trimmedQuery) {
        if (trimmedQuery.isEmpty()) emptyList() else root?.searchFolders(trimmedQuery).orEmpty()
    }
    val mediaResults = remember(root, trimmedQuery) {
        if (trimmedQuery.isEmpty()) emptyList() else root?.searchMedia(trimmedQuery).orEmpty()
    }

    // Opening a media search result has to resolve to the same (folder path, index within that
    // folder's own sorted list) pair MediaViewerScreen expects -- the same reason
    // GalleryScreen/MediaViewerScreen each independently recompute sortedMedia with the folder's
    // own effectiveFolderSort rather than hardcoding an order (see sortedMedia's doc comment).
    fun openMediaResult(item: MediaItem) {
        val siblings = root?.findNode(item.folderPath)?.items ?: listOf(item)
        val order = effectiveFolderSort(item.folderPath, folderSort, folderSortOverrides)
        val index = sortedMedia(siblings, order).indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        onOpenMedia(item.folderPath, index)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onDismiss)
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            TextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Search photos, videos, folders") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Close search")
                            }
                        },
                        actions = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                    )
                },
            ) { padding ->
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                when {
                    trimmedQuery.isEmpty() -> {
                        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "Search for photos, videos, or folders by name.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    folderResults.isEmpty() && mediaResults.isEmpty() -> {
                        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No results for \"$trimmedQuery\".", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                            if (folderResults.isNotEmpty()) {
                                item { SearchSectionHeader("Folders") }
                                items(folderResults, key = { "folder:${it.path}" }) { folder ->
                                    FolderListRow(
                                        folder = folder,
                                        isHidden = false,
                                        isSelected = false,
                                        cover = null,
                                        includedFolders = includedFolders,
                                        thumbnailSizeDp = folderRowSize,
                                        thumbnailWidthDp = folderThumbnailWidth,
                                        onClick = { onOpenFolder(folder.path) },
                                    )
                                }
                            }
                            if (mediaResults.isNotEmpty()) {
                                item { SearchSectionHeader("Photos & videos") }
                                items(mediaResults, key = { "media:${it.id}" }) { mediaItem ->
                                    MediaListRow(
                                        item = mediaItem,
                                        isHidden = false,
                                        isSelected = false,
                                        thumbnailSizeDp = mediaRowSize,
                                        thumbnailWidthDp = mediaThumbnailWidth,
                                        onClick = { openMediaResult(mediaItem) },
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

@Composable
private fun SearchSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
