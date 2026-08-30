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
 * gallery's multi-select toolbar (acting on every selected item/folder). Every action
 * beyond Edit/Share/Delete lives behind the overflow menu as a plain text label -- the
 * exact set of what's applicable (Rename, Set cover, Exclude, ...) changes depending on
 * what's selected, so the caller builds that list rather than this component guessing.
 */
@Composable
fun MediaActionBar(
    onEdit: (() -> Unit)?,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    overflowActions: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        if (onEdit != null) {
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
        }
        IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        if (overflowActions.isNotEmpty()) {
            Box {
                IconButton(onClick = { overflowExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                    overflowActions.forEach { (label, action) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { overflowExpanded = false; action() },
                        )
                    }
                }
            }
        }
    }
}
