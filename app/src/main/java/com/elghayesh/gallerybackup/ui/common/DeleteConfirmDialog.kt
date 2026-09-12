package com.elghayesh.gallerybackup.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel

/**
 * Returns a callback that requests deletion of the given items, showing a confirmation
 * dialog first (with "skip recycle bin" and "don't ask again this session" options)
 * unless the user already opted out of the dialog earlier in this session.
 */
@Composable
fun rememberDeleteRequester(viewModel: GalleryViewModel): (List<MediaItem>) -> Unit {
    val dontAskAgain by viewModel.dontAskAgainDelete.collectAsState()
    val skipTrashDefault by viewModel.skipRecycleBinDefault.collectAsState()
    var pendingItems by remember { mutableStateOf<List<MediaItem>?>(null) }

    val items = pendingItems
    if (items != null) {
        DeleteConfirmDialog(
            itemCount = items.size,
            initialSkipTrash = skipTrashDefault,
            onConfirm = { skipTrash, dontAskAgainChecked ->
                viewModel.setSkipRecycleBinDefault(skipTrash)
                viewModel.setDontAskAgainDelete(dontAskAgainChecked)
                viewModel.deleteMediaItems(items, skipTrash)
                pendingItems = null
            },
            onDismiss = { pendingItems = null },
        )
    }

    return { requestedItems ->
        if (dontAskAgain) {
            viewModel.deleteMediaItems(requestedItems, skipTrashDefault)
        } else {
            pendingItems = requestedItems
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    itemCount: Int,
    initialSkipTrash: Boolean,
    onConfirm: (skipTrash: Boolean, dontAskAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var skipTrash by remember { mutableStateOf(initialSkipTrash) }
    var dontAskAgain by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (itemCount == 1) "Delete this item?" else "Delete $itemCount items?") },
        text = {
            Column {
                Text(
                    if (skipTrash) {
                        "This permanently deletes -- it cannot be recovered."
                    } else {
                        "Moves to your device's recycle bin, recoverable for about 30 days."
                    },
                )
                Row(
                    Modifier.fillMaxWidth().clickable { skipTrash = !skipTrash },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = skipTrash, onCheckedChange = { skipTrash = it })
                    Text("Skip recycle bin (delete permanently)")
                }
                Row(
                    Modifier.fillMaxWidth().clickable { dontAskAgain = !dontAskAgain },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = dontAskAgain, onCheckedChange = { dontAskAgain = it })
                    Text("Don't ask me again this session")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(skipTrash, dontAskAgain) }) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
