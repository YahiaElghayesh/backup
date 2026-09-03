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
                    Spacer(Modifier.height(8.dp))
                    Text("Folders", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ViewType.entries.forEach { type ->
                            FilterChip(
                                selected = type == folderViewType,
                                onClick = { viewModel.setFolderViewType(type) },
                                label = { Text(if (type == ViewType.GRID) "Grid" else "List") },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Photos & videos", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ViewType.entries.forEach { type ->
                            FilterChip(
                                selected = type == mediaViewType,
                                onClick = { viewModel.setMediaViewType(type) },
                                label = { Text(if (type == ViewType.GRID) "Grid" else "List") },
                            )
                        }
                    }
                }
                HorizontalDivider()
            }

            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Folder tile size: $folderGridColumns per row", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = folderGridColumns.toFloat(),
                        onValueChange = { viewModel.setFolderGridColumns(it.roundToInt()) },
                        valueRange = 2f..6f,
                        steps = 3,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Photo/video tile size: $mediaGridColumns per row", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = mediaGridColumns.toFloat(),
                        onValueChange = { viewModel.setMediaGridColumns(it.roundToInt()) },
                        valueRange = 2f..6f,
                        steps = 3,
                    )
                }
                HorizontalDivider()
            }

            item {
                Column(Modifier.padding(16.dp)) {
                    Text("List view row sizes", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Only used when the list view is selected, instead of the grid.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Folder row size: ${folderRowSize}dp", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = folderRowSize.toFloat(),
                        onValueChange = { viewModel.setFolderRowSize(it.roundToInt()) },
                        valueRange = 40f..112f,
                        steps = 8,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Photo/video row size: ${mediaRowSize}dp", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = mediaRowSize.toFloat(),
                        onValueChange = { viewModel.setMediaRowSize(it.roundToInt()) },
                        valueRange = 40f..112f,
                        steps = 8,
                    )
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
