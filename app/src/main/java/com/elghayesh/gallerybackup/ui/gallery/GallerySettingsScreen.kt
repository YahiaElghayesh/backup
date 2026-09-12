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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** Which categorized sub-menu, if any, is currently open. Null means the top-level menu list. */
private enum class SettingsSection { APPEARANCE, LAYOUT, RECYCLE_BIN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GallerySettingsScreen(
    viewModel: GalleryViewModel,
    onOpenFolderExplorer: () -> Unit,
    onBack: () -> Unit,
) {
    var openSection by remember { mutableStateOf<SettingsSection?>(null) }
    val isCheckingForUpdate by UpdateCheckCoordinator.isChecking.collectAsState()
    val lastCheckFoundUpdate by UpdateCheckCoordinator.lastCheckFoundUpdate.collectAsState()

    when (openSection) {
        SettingsSection.APPEARANCE -> AppearanceSettingsScreen(viewModel, onBack = { openSection = null })
        SettingsSection.LAYOUT -> LayoutSettingsScreen(viewModel, onBack = { openSection = null })
        SettingsSection.RECYCLE_BIN -> RecycleBinSettingsScreen(viewModel, onBack = { openSection = null })
        null -> Scaffold(
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
                    SettingsMenuRow(
                        title = "Appearance",
                        subtitle = "Theme and accent color.",
                        onClick = { openSection = SettingsSection.APPEARANCE },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsMenuRow(
                        title = "Layout",
                        subtitle = "Grid or list, tile/row size, and pinning short lists to the bottom.",
                        onClick = { openSection = SettingsSection.LAYOUT },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsMenuRow(
                        title = "Recycle bin",
                        subtitle = "Empty the recycle bin, or change how long deleted items stay in it.",
                        onClick = { openSection = SettingsSection.RECYCLE_BIN },
                    )
                    HorizontalDivider()
                }
                item {
                    SettingsMenuRow(
                        title = "Choose gallery folders",
                        subtitle = "Pin folders to the gallery's home page, or hide folders entirely -- " +
                            "file-explorer style, with a separate checkbox for each.",
                        onClick = onOpenFolderExplorer,
                    )
                    HorizontalDivider()
                }
                item {
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
}

@Composable
private fun SettingsMenuRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubScaffold(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding -> content(Modifier.padding(padding)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSettingsScreen(viewModel: GalleryViewModel, onBack: () -> Unit) {
    val themeMode by viewModel.themeMode.collectAsState()
    val accentColor by viewModel.accentColor.collectAsState()

    SettingsSubScaffold(title = "Appearance", onBack = onBack) { modifier ->
        Column(modifier.fillMaxWidth().padding(16.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleSmall)
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
            Spacer(Modifier.height(20.dp))
            Text("Accent color", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            AccentColor.entries.chunked(8).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { color ->
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
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayoutSettingsScreen(viewModel: GalleryViewModel, onBack: () -> Unit) {
    val folderViewType by viewModel.folderViewType.collectAsState()
    val mediaViewType by viewModel.mediaViewType.collectAsState()
    val folderGridColumns by viewModel.folderGridColumns.collectAsState()
    val mediaGridColumns by viewModel.mediaGridColumns.collectAsState()
    val folderRowSize by viewModel.folderRowSize.collectAsState()
    val mediaRowSize by viewModel.mediaRowSize.collectAsState()
    val folderThumbnailWidth by viewModel.folderThumbnailWidth.collectAsState()
    val mediaThumbnailWidth by viewModel.mediaThumbnailWidth.collectAsState()
    val pinContentToBottom by viewModel.pinContentToBottom.collectAsState()

    SettingsSubScaffold(title = "Layout", onBack = onBack) { modifier ->
        LazyColumn(modifier = modifier.fillMaxWidth()) {
            item {
                Column(Modifier.padding(16.dp)) {
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
                        thumbnailWidth = folderThumbnailWidth,
                        onThumbnailWidthChange = { viewModel.setFolderThumbnailWidth(it) },
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
                        thumbnailWidth = mediaThumbnailWidth,
                        onThumbnailWidthChange = { viewModel.setMediaThumbnailWidth(it) },
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
                                "the bottom instead of the top -- doesn't change sort order, and stops " +
                                "applying the moment there's enough content to fill the screen, so it " +
                                "never fights normal scrolling once a list is actually long.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = pinContentToBottom, onCheckedChange = { viewModel.setPinContentToBottom(it) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecycleBinSettingsScreen(viewModel: GalleryViewModel, onBack: () -> Unit) {
    val retentionDays by viewModel.trashRetentionDays.collectAsState()
    val trashedItems by viewModel.trashedItems.collectAsState()
    var confirmEmpty by remember { mutableStateOf(false) }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty recycle bin?") },
            text = { Text("All ${trashedItems.size} item(s) in the trash will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.emptyTrash(); confirmEmpty = false }) { Text("Empty") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") }
            },
        )
    }

    SettingsSubScaffold(title = "Recycle bin", onBack = onBack) { modifier ->
        Column(modifier.fillMaxWidth().padding(16.dp)) {
            Text("Retention period", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Deleted items are automatically removed forever after this many days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text("$retentionDays days", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = retentionDays.toFloat(),
                onValueChange = { viewModel.setTrashRetentionDays(it.roundToInt()) },
                valueRange = 1f..90f,
                steps = 88,
            )
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))
            Text("Empty recycle bin", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Permanently deletes everything currently in the trash (${trashedItems.size} item(s)). This cannot be undone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirmEmpty = true }, enabled = trashedItems.isNotEmpty()) {
                Text("Empty recycle bin")
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
    thumbnailWidth: Int,
    onThumbnailWidthChange: (Int) -> Unit,
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
        Text("${rowSize}dp thumbnail height", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = rowSize.toFloat(),
            onValueChange = { onRowSizeChange(it.roundToInt()) },
            valueRange = 40f..112f,
            steps = 8,
        )
        Spacer(Modifier.height(8.dp))
        Text("${thumbnailWidth}dp thumbnail width", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = thumbnailWidth.toFloat(),
            onValueChange = { onThumbnailWidthChange(it.roundToInt()) },
            valueRange = 40f..112f,
            steps = 8,
        )
    }
}
