package com.elghayesh.gallerybackup.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.elghayesh.gallerybackup.data.settings.AccentColor
import com.elghayesh.gallerybackup.data.settings.FolderCover

/** Lets the user give a folder a custom cover: plain text over a solid background color. */
@Composable
fun FolderCoverDialog(
    folderName: String,
    current: FolderCover?,
    onConfirm: (FolderCover?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current?.text ?: folderName) }
    var selectedColor by remember {
        mutableStateOf(AccentColor.entries.find { it.seed == current?.colorSeed } ?: AccentColor.BLUE)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folder cover") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Cover text") })
                Spacer(Modifier.height(12.dp))
                Text("Background color", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccentColor.entries.forEach { color ->
                        Row(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(color.seed))
                                .border(
                                    width = if (color == selectedColor) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape,
                                )
                                .clickable { selectedColor = color },
                        ) {}
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(if (text.isBlank()) null else FolderCover(text.trim(), selectedColor.seed))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = { onConfirm(null) }) { Text("Remove cover") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
