package com.elghayesh.gallerybackup.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.elghayesh.gallerybackup.data.media.FolderNode
import com.elghayesh.gallerybackup.data.media.findNode
import com.elghayesh.gallerybackup.data.media.promotedChildren
import com.elghayesh.gallerybackup.data.settings.ViewType
import com.elghayesh.gallerybackup.ui.gallery.FolderGridTile
import com.elghayesh.gallerybackup.ui.gallery.FolderListRow
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel
import com.elghayesh.gallerybackup.ui.gallery.MediaGridTile
import com.elghayesh.gallerybackup.ui.gallery.MediaListRow
import com.elghayesh.gallerybackup.ui.gallery.effectiveFolderSort
import com.elghayesh.gallerybackup.ui.gallery.sortedFolders
import com.elghayesh.gallerybackup.ui.gallery.sortedMedia

/**
 * A full-screen, navigable browser of the real folder tree for picking a Move to/Copy to
 * destination (or any other "pick a folder" flow). Reuses the exact same folder tiles the main
 * gallery uses -- same grid/list layout choice, tile size, and cover photos, driven by the same
 * [GalleryViewModel] settings -- rather than a separate, plainer file-explorer look, so a
 * destination is recognizable at a glance the same way it is in the gallery itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderTreePickerDialog(
    viewModel: GalleryViewModel,
    root: FolderNode?,
    title: String,
    onPick: (path: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentPath by remember { mutableStateOf("") }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    val node = root?.findNode(currentPath)

    // The on-screen arrow already goes up one folder level (or dismisses at the root) via its own
    // onClick below -- but this dialog's system/gesture back button is a separate code path that,
    // without this, falls straight through to the Dialog's own default onDismissRequest, cancelling
    // the whole move/copy instead of just stepping back inside the picker. The BackHandler call
    // itself has to live inside the Dialog(...) content lambda below (not here) to register
    // against that window's own back dispatcher rather than the screen behind it.
    val goBack = {
        if (currentPath.isEmpty()) onDismiss() else currentPath = currentPath.substringBeforeLast('/', "")
    }

    val folderViewType by viewModel.folderViewType.collectAsState()
    val folderGridColumns by viewModel.folderGridColumns.collectAsState()
    val folderRowSize by viewModel.folderRowSize.collectAsState()
    val folderCovers by viewModel.folderCovers.collectAsState()
    val includedFolders by viewModel.includedFolders.collectAsState()
    val folderSort by viewModel.folderSort.collectAsState()
    val folderSortOverrides by viewModel.folderSortOverrides.collectAsState()
    val folderThumbnailWidth by viewModel.folderThumbnailWidth.collectAsState()
    val mediaRowSize by viewModel.mediaRowSize.collectAsState()
    val mediaThumbnailWidth by viewModel.mediaThumbnailWidth.collectAsState()
    val pinContentToBottom by viewModel.pinContentToBottom.collectAsState()

    // Same promotion (a pinned folder is hidden from its real parent's own listing, and -- at the
    // very top level -- surfaces instead as its own separate tile there) and sort order the
    // gallery itself would use browsing this same path, so a destination looks exactly like the
    // folder it really is instead of a differently-ordered file-explorer view. Without mirroring
    // the root's "pinned folders replace the real top-level listing" rule here too, a user who's
    // pinned every one of their top-level folders would see this picker's root as completely
    // empty -- promotedChildren excludes each pinned folder from its real parent, but only the
    // gallery's own root re-adds them back as pinnedExtras; this picker used to just drop them.
    val children = remember(node, includedFolders, folderSort, folderSortOverrides, currentPath, root) {
        val ownChildren = when {
            currentPath.isNotEmpty() -> node?.promotedChildren(includedFolders) ?: emptyList()
            includedFolders.isEmpty() -> node?.children?.values?.toList() ?: emptyList()
            else -> emptyList()
        }
        val pinnedExtras = if (currentPath.isEmpty()) includedFolders.mapNotNull { root?.findNode(it) } else emptyList()
        sortedFolders(ownChildren + pinnedExtras, effectiveFolderSort(currentPath, folderSort, folderSortOverrides), includedFolders)
    }

    // The current folder's own photos/videos, shown below its subfolders -- so picking "this
    // folder" as a Move/Copy destination (or just browsing into it) shows what's already there,
    // not just an empty-looking list of subfolders. [root] is already the gallery's own
    // hidden/trashed-filtered tree (see the call site), so these need no separate filtering here.
    // Purely informational: rendered with no onClick/onLongClick, since this dialog's only
    // actionable items are folders (to navigate into) and the bottom bar's own pick button.
    val mediaItems = remember(node, folderSort, folderSortOverrides, currentPath) {
        sortedMedia(node?.items ?: emptyList(), effectiveFolderSort(currentPath, folderSort, folderSortOverrides))
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onConfirm = { name ->
                viewModel.createFolder(currentPath, name)
                currentPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                showCreateFolderDialog = false
            },
            onDismiss = { showCreateFolderDialog = false },
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = goBack)
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (currentPath.isEmpty()) title else currentPath.substringAfterLast('/')) },
                        navigationIcon = {
                            IconButton(onClick = goBack) {
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
                if (children.isEmpty() && mediaItems.isEmpty()) {
                    Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Nothing here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (folderViewType == ViewType.GRID) {
                    // See GalleryScreen's own pin-to-bottom comment: a loose (not exact) max-height
                    // bound lets the grid shrink-wrap its content and the bottom alignment then pins
                    // that short content low, while content that already fills or overflows the
                    // viewport clamps to the full height and the alignment becomes a no-op -- same
                    // mechanism, applied here too, so this picker follows the setting exactly like
                    // the main gallery instead of always anchoring to the top regardless of it.
                    BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(folderGridColumns),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .align(if (pinContentToBottom) Alignment.BottomStart else Alignment.TopStart)
                                .fillMaxWidth()
                                .then(
                                    if (pinContentToBottom) Modifier.heightIn(max = maxHeight) else Modifier.fillMaxHeight(),
                                ),
                        ) {
                            items(children, key = { "folder:${it.path}" }) { folder ->
                                FolderGridTile(
                                    folder = folder,
                                    isHidden = false,
                                    isSelected = false,
                                    cover = folderCovers[folder.path],
                                    includedFolders = includedFolders,
                                    onClick = { currentPath = folder.path },
                                )
                            }
                            items(mediaItems, key = { "media:${it.id}" }) { item ->
                                MediaGridTile(item = item, isHidden = false, isSelected = false)
                            }
                        }
                    }
                } else {
                    BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier
                                .align(if (pinContentToBottom) Alignment.BottomStart else Alignment.TopStart)
                                .fillMaxWidth()
                                .then(
                                    if (pinContentToBottom) Modifier.heightIn(max = maxHeight) else Modifier.fillMaxHeight(),
                                ),
                        ) {
                            listItems(children, key = { "folder:${it.path}" }) { folder ->
                                FolderListRow(
                                    folder = folder,
                                    isHidden = false,
                                    isSelected = false,
                                    cover = folderCovers[folder.path],
                                    includedFolders = includedFolders,
                                    thumbnailSizeDp = folderRowSize,
                                    thumbnailWidthDp = folderThumbnailWidth,
                                    onClick = { currentPath = folder.path },
                                    onLongClick = {},
                                )
                            }
                            listItems(mediaItems, key = { "media:${it.id}" }) { item ->
                                MediaListRow(
                                    item = item,
                                    isHidden = false,
                                    isSelected = false,
                                    thumbnailSizeDp = mediaRowSize,
                                    thumbnailWidthDp = mediaThumbnailWidth,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
