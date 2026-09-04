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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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

private enum class CoverTextSize(val scale: Float, val label: String) {
    SMALL(0.7f, "Small"),
    MEDIUM(1f, "Medium"),
    LARGE(1.5f, "Large"),
    EXTRA_LARGE(2f, "Extra large"),
}

/** Lets the user give one or more folders a custom cover: plain text over a solid background
 * color, or (single-folder only) a specific photo/video frame picked from the folder's own items.
 * Without either, the tile falls back to the folder's own first item, as it always has.
 *
 * With more than one folder in [folders], the exact same controls apply to every folder at once
 * (color, size, bold, alternating colors), plus a checkbox to use each folder's own name as its
 * text instead of one typed-in string for all of them -- rather than a separate, stripped-down
 * bulk action with none of the single-folder dialog's customization. */
@Composable
fun FolderCoverDialog(
    folders: List<FolderNode>,
    current: FolderCover?,
    onConfirm: (Map<String, FolderCover?>) -> Unit,
    onDismiss: () -> Unit,
) {
    val isMulti = folders.size > 1
    var mode by remember { mutableStateOf(if (current is FolderCover.Photo) CoverMode.PHOTO else CoverMode.TEXT) }
    var useFolderNames by remember { mutableStateOf(isMulti) }
    var text by remember { mutableStateOf((current as? FolderCover.Text)?.text ?: folders.firstOrNull()?.name ?: "") }
    var selectedColor by remember {
        mutableStateOf(AccentColor.entries.find { it.seed == (current as? FolderCover.Text)?.colorSeed } ?: AccentColor.BLUE)
    }
    var alternateColors by remember { mutableStateOf(false) }
    var selectedAlternateColors by remember { mutableStateOf(setOf<AccentColor>()) }
    var selectedSize by remember {
        val currentScale = (current as? FolderCover.Text)?.sizeScale ?: 1f
        mutableStateOf(CoverTextSize.entries.minBy { kotlin.math.abs(it.scale - currentScale) })
    }
    var bold by remember { mutableStateOf((current as? FolderCover.Text)?.bold ?: false) }
    var selectedPhotoUri by remember { mutableStateOf((current as? FolderCover.Photo)?.uri) }
    val items = remember(folders) {
        if (isMulti) emptyList() else folders.firstOrNull()?.allItemsRecursive()?.sortedByDescending { it.dateModifiedSec } ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isMulti) "Folder covers (${folders.size})" else "Folder cover") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == CoverMode.TEXT, onClick = { mode = CoverMode.TEXT }, label = { Text("Text") })
                    if (!isMulti) {
                        FilterChip(selected = mode == CoverMode.PHOTO, onClick = { mode = CoverMode.PHOTO }, label = { Text("Photo") })
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (mode == CoverMode.TEXT) {
                    if (isMulti) {
                        CheckboxRow(
                            checked = useFolderNames,
                            onCheckedChange = { useFolderNames = it },
                            label = "Use each folder's own name as its text",
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (!useFolderNames) {
                        OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Cover text") })
                        Spacer(Modifier.height(12.dp))
                    }
                    Text("Text size", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CoverTextSize.entries.forEach { size ->
                            FilterChip(
                                selected = size == selectedSize,
                                onClick = { selectedSize = size },
                                label = { Text(size.label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    CheckboxRow(checked = bold, onCheckedChange = { bold = it }, label = "Bold")
                    Spacer(Modifier.height(12.dp))
                    if (isMulti) {
                        CheckboxRow(
                            checked = alternateColors,
                            onCheckedChange = { alternateColors = it },
                            label = "Alternate between colors",
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        if (alternateColors) "Colors to alternate between" else "Background color",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    AccentColor.entries.chunked(8).forEach { rowColors ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowColors.forEach { color ->
                                val isSelected = if (alternateColors) color in selectedAlternateColors else color == selectedColor
                                Row(
                                    Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color(color.seed))
                                        .border(
                                            width = if (isSelected) 3.dp else 0.dp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            shape = CircleShape,
                                        )
                                        .clickable {
                                            if (alternateColors) {
                                                selectedAlternateColors = if (color in selectedAlternateColors) {
                                                    selectedAlternateColors - color
                                                } else {
                                                    selectedAlternateColors + color
                                                }
                                            } else {
                                                selectedColor = color
                                            }
                                        },
                                ) {}
                            }
                        }
                        Spacer(Modifier.height(6.dp))
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
            val confirmEnabled = when (mode) {
                CoverMode.TEXT -> (useFolderNames || text.isNotBlank()) && (!alternateColors || selectedAlternateColors.isNotEmpty())
                CoverMode.PHOTO -> selectedPhotoUri != null
            }
            TextButton(
                enabled = confirmEnabled,
                onClick = {
                    val perFolder = when (mode) {
                        CoverMode.PHOTO -> {
                            val folder = folders.first()
                            selectedPhotoUri?.let { mapOf<String, FolderCover?>(folder.path to FolderCover.Photo(it)) } ?: emptyMap()
                        }
                        CoverMode.TEXT -> {
                            val colors = if (alternateColors && selectedAlternateColors.isNotEmpty()) {
                                selectedAlternateColors.toList()
                            } else {
                                listOf(selectedColor)
                            }
                            folders.withIndex().associate { (i, folder) ->
                                val folderText = if (useFolderNames) folder.name else text
                                val color = colors[i % colors.size]
                                folder.path to if (folderText.isBlank()) {
                                    null
                                } else {
                                    FolderCover.Text(folderText.trim(), color.seed, selectedSize.scale, bold)
                                }
                            }
                        }
                    }
                    onConfirm(perFolder)
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (isMulti || current != null) {
                    TextButton(onClick = { onConfirm(folders.associate { it.path to null }) }) { Text("Remove cover") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun CheckboxRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label)
    }
}
