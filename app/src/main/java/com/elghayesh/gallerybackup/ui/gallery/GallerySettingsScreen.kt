package com.elghayesh.gallerybackup.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.elghayesh.gallerybackup.data.settings.AccentColor
import com.elghayesh.gallerybackup.data.settings.ThemeMode
import com.elghayesh.gallerybackup.data.settings.ViewType
import com.elghayesh.gallerybackup.data.update.UpdateCheckCoordinator
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GallerySettingsScreen(
    viewModel: GalleryViewModel,
    onOpenFolderExplorer: () -> Unit,
    onBack: () -> Unit,
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val accentColor by viewModel.accentColor.collectAsState()
    val folderViewType by viewModel.folderViewType.collectAsState()
    val mediaViewType by viewModel.mediaViewType.collectAsState()
    val folderGridColumns by viewModel.folderGridColumns.collectAsState()
    val mediaGridColumns by viewModel.mediaGridColumns.collectAsState()
    val folderRowSize by viewModel.folderRowSize.collectAsState()
    val mediaRowSize by viewModel.mediaRowSize.collectAsState()
    val pinContentToBottom by viewModel.pinContentToBottom.collectAsState()
    val isCheckingForUpdate by UpdateCheckCoordinator.isChecking.collectAsState()
    val lastCheckFoundUpdate by UpdateCheckCoordinator.lastCheckFoundUpdate.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gallery settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxWidth()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = mode == themeMode,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Accent color", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AccentColor.entries.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(color.seed))
                                    .border(
                                        width = if (color == accentColor) 3.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape,
                                    )
                                    .clickable { viewModel.setAccentColor(color) },
                            )
                        }
                    }
                }
                HorizontalDivider()
            }

            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Layout", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Grid or list, and tile/row size, chosen separately for folders and for photos & videos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    LayoutTargetSettings(
                        label = "Folders",
                        viewType = folderViewType,
                        onViewTypeChange = { viewModel.setFolderViewType(it) },
                        gridColumns = folderGridColumns,
                        onGridColumnsChange = { viewModel.setFolderGridColumns(it) },
                        rowSize = folderRowSize,
                        onRowSizeChange = { viewModel.setFolderRowSize(it) },
                    )
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(20.dp))
                    LayoutTargetSettings(
                        label = "Photos & videos",
                        viewType = mediaViewType,
                        onViewTypeChange = { viewModel.setMediaViewType(it) },
                        gridColumns = mediaGridColumns,
                        onGridColumnsChange = { viewModel.setMediaGridColumns(it) },
                        rowSize = mediaRowSize,
                        onRowSizeChange = { viewModel.setMediaRowSize(it) },
                    )
                }
                HorizontalDivider()
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Pin content to the bottom", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "When a folder or list of photos doesn't fill the screen, anchor it to " +
                                "the bottom instead of the top -- doesn't change sort order, just makes " +
                                "short lists easier to reach with your thumb.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = pinContentToBottom, onCheckedChange = { viewModel.setPinContentToBottom(it) })
                }
                HorizontalDivider()
            }

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenFolderExplorer)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Choose gallery folders", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Pin folders to the gallery's home page, or hide folders entirely -- " +
                                "file-explorer style, with a separate checkbox for each.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
                }
            }

            item {
                HorizontalDivider()
                Column(Modifier.padding(16.dp)) {
                    Text("Updates", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { UpdateCheckCoordinator.requestCheck() },
                        enabled = !isCheckingForUpdate,
                    ) {
                        Text(if (isCheckingForUpdate) "Checking..." else "Check for updates")
                    }
                    if (!isCheckingForUpdate && lastCheckFoundUpdate == false) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "You're on the latest version.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** One target's (folders, or photos & videos) view type plus whichever size control actually
 * applies to it right now -- tile columns for grid, row height for list -- instead of showing
 * both sliders regardless of which view is selected. */
@Composable
private fun LayoutTargetSettings(
    label: String,
    viewType: ViewType,
    onViewTypeChange: (ViewType) -> Unit,
    gridColumns: Int,
    onGridColumnsChange: (Int) -> Unit,
    rowSize: Int,
    onRowSizeChange: (Int) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ViewType.entries.forEach { type ->
            FilterChip(
                selected = type == viewType,
                onClick = { onViewTypeChange(type) },
                label = { Text(if (type == ViewType.GRID) "Grid" else "List") },
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    if (viewType == ViewType.GRID) {
        Text("$gridColumns per row", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = gridColumns.toFloat(),
            onValueChange = { onGridColumnsChange(it.roundToInt()) },
            valueRange = 2f..6f,
            steps = 3,
        )
    } else {
        Text("${rowSize}dp row size", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = rowSize.toFloat(),
            onValueChange = { onRowSizeChange(it.roundToInt()) },
            valueRange = 40f..112f,
            steps = 8,
        )
    }
}
