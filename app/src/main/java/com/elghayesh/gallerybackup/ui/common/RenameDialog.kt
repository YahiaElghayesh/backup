package com.elghayesh.gallerybackup.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun RenameDialog(title: String, initialName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initialName) }
    // A rename can take a moment (it copies bytes into a new file before trashing the original),
    // and the dialog doesn't disappear the instant the button is tapped -- recomposition takes at
    // least a frame. Without this, a fast double-tap fires onConfirm twice before showRenameDialog
    // ever flips to false at the call site, racing two copies of the same rename against each
    // other. The ViewModel now also guards against that re-entrantly, but disabling here gives the
    // user visible feedback instead of a silent no-op on the second tap.
    var confirmed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, enabled = !confirmed)
        },
        confirmButton = {
            TextButton(
                enabled = !confirmed,
                onClick = {
                    if (text.isNotBlank()) {
                        confirmed = true
                        onConfirm(text.trim())
                    }
                },
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !confirmed) { Text("Cancel") }
        },
    )
}
