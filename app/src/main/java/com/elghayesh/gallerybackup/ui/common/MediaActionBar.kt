package com.elghayesh.gallerybackup.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * The action row shared by the media viewer (acting on the single open item) and the
 * gallery's multi-select toolbar (acting on every selected item). Move/Copy/Properties
 * live behind an overflow menu -- kept to text labels rather than icons, since there
 * isn't a Material icon name for "move to folder" this codebase can be fully sure of.
 */
@Composable
fun MediaActionBar(
    onEdit: (() -> Unit)?,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onMoveTo: () -> Unit,
    onCopyTo: () -> Unit,
    onProperties: () -> Unit,
    hideLabel: String? = null,
    onToggleHidden: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        if (onEdit != null) {
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
        }
        IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        Box {
            IconButton(onClick = { overflowExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                if (onToggleHidden != null && hideLabel != null) {
                    DropdownMenuItem(
                        text = { Text(hideLabel) },
                        onClick = { overflowExpanded = false; onToggleHidden() },
                    )
                }
                DropdownMenuItem(text = { Text("Move to...") }, onClick = { overflowExpanded = false; onMoveTo() })
                DropdownMenuItem(text = { Text("Copy to...") }, onClick = { overflowExpanded = false; onCopyTo() })
                DropdownMenuItem(text = { Text("Properties") }, onClick = { overflowExpanded = false; onProperties() })
            }
        }
    }
}
