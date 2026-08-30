package com.elghayesh.gallerybackup.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.elghayesh.gallerybackup.data.media.FolderNode
import com.elghayesh.gallerybackup.data.media.flattenAllFolders

/** A simple "pick an existing folder" dialog, used for both Move to and Copy to. */
@Composable
fun FolderPickerDialog(
    root: FolderNode?,
    title: String,
    onPick: (path: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val folders = root?.flattenAllFolders()?.sortedBy { it.path.lowercase() } ?: emptyList()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(folders, key = { it.path }) { folder ->
                    Text(
                        folder.path,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(folder.path) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
