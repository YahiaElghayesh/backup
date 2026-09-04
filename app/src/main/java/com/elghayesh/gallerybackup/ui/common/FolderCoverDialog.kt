package com.elghayesh.gallerybackup.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elghayesh.gallerybackup.data.media.FolderNode
import com.elghayesh.gallerybackup.data.media.allItemsRecursive
import com.elghayesh.gallerybackup.data.settings.AccentColor
import com.elghayesh.gallerybackup.data.settings.FolderCover

private enum class CoverMode { TEXT, PHOTO }

/** Lets the user give a folder a custom cover: plain text over a solid background color, or a
 * specific photo/video frame picked from the folder's own items (including subfolders). Without
 * either, the tile falls back to the folder's own first item, as it always has. */
@Composable
fun FolderCoverDialog(
    folder: FolderNode,
    current: FolderCover?,
    onConfirm: (FolderCover?) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(if (current is FolderCover.Photo) CoverMode.PHOTO else CoverMode.TEXT) }
    var text by remember { mutableStateOf((current as? FolderCover.Text)?.text ?: folder.name) }
    var selectedColor by remember {
        mutableStateOf(AccentColor.entries.find { it.seed == (current as? FolderCover.Text)?.colorSeed } ?: AccentColor.BLUE)
    }
    var selectedPhotoUri by remember { mutableStateOf((current as? FolderCover.Photo)?.uri) }
    val items = remember(folder) { folder.allItemsRecursive().sortedByDescending { it.dateModifiedSec } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folder cover") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == CoverMode.TEXT, onClick = { mode = CoverMode.TEXT }, label = { Text("Text") })
                    FilterChip(selected = mode == CoverMode.PHOTO, onClick = { mode = CoverMode.PHOTO }, label = { Text("Photo") })
                }
                Spacer(Modifier.height(12.dp))
                if (mode == CoverMode.TEXT) {
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
                } else if (items.isEmpty()) {
                    Text(
                        "No photos or videos in this folder yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                    ) {
                        items(items, key = { it.id }) { item ->
                            val uriStr = item.uri.toString()
                            val selected = uriStr == selectedPhotoUri
                            Box(
                                Modifier
                                    .padding(2.dp)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .then(
                                        if (selected) {
                                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .clickable { selectedPhotoUri = uriStr },
                            ) {
                                AsyncImage(
                                    model = item.uri,
                                    contentDescription = item.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = mode == CoverMode.TEXT || selectedPhotoUri != null,
                onClick = {
                    val cover = when (mode) {
                        CoverMode.TEXT -> if (text.isBlank()) null else FolderCover.Text(text.trim(), selectedColor.seed)
                        CoverMode.PHOTO -> selectedPhotoUri?.let { FolderCover.Photo(it) }
                    }
                    onConfirm(cover)
                },
            ) { Text("Save") }
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
