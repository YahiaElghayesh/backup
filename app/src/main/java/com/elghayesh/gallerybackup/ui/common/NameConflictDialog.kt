package com.elghayesh.gallerybackup.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.elghayesh.gallerybackup.ui.gallery.NameConflictResolution

/**
 * Shown when a Move/Copy's incoming item(s) collide by name with something already at the
 * destination -- see GalleryViewModel.requestTransfer/resolveNameConflict. Used to auto-resolve
 * silently (MediaStore just renamed the incoming file), which meant a move or copy could land
 * under a name the user never asked for with no way to say otherwise -- this asks explicitly
 * instead: replace what's there, keep both (the same auto-numbered outcome as before, now a
 * deliberate choice), or cancel the whole transfer.
 */
@Composable
fun NameConflictDialog(conflictingNames: List<String>, onResolve: (NameConflictResolution) -> Unit) {
    AlertDialog(
        onDismissRequest = { onResolve(NameConflictResolution.CANCEL) },
        title = { Text("Already exists") },
        text = {
            val subject = if (conflictingNames.size == 1) {
                "\"${conflictingNames.first()}\" already exists in this folder."
            } else {
                "${conflictingNames.size} of these already exist in this folder: ${conflictingNames.joinToString(", ")}"
            }
            Text("$subject What do you want to do?")
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onResolve(NameConflictResolution.REPLACE) }) { Text("Replace") }
                TextButton(onClick = { onResolve(NameConflictResolution.KEEP_BOTH) }) { Text("Keep both") }
            }
        },
        dismissButton = {
            TextButton(onClick = { onResolve(NameConflictResolution.CANCEL) }) { Text("Cancel") }
        },
    )
}
