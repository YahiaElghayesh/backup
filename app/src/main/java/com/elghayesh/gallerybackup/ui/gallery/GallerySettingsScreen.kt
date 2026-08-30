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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elghayesh.gallerybackup.data.media.flattenAllFolders
import com.elghayesh.gallerybackup.data.settings.AccentColor
import com.elghayesh.gallerybackup.data.settings.ThemeMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GallerySettingsScreen(
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
) {
    val root by viewModel.root.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val accentColor by viewModel.accentColor.collectAsState()
    val gridColumns by viewModel.gridColumns.collectAsState()
    val excludedFolders by viewModel.excludedFolders.collectAsState()
    val hiddenFolders by viewModel.hiddenFolders.collectAsState()

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
                    Text("Grid columns: $gridColumns", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = gridColumns.toFloat(),
                        onValueChange = { viewModel.setGridColumns(it.roundToInt()) },
                        valueRange = 2f..5f,
                        steps = 2,
                    )
                }
                HorizontalDivider()
            }

            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Folder visibility", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Excluded folders never show in the gallery. Hidden folders only show when " +
                            "\"Show hidden items\" is turned on from the gallery's menu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("Folder", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        Text("Show", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(28.dp))
                        Text("Hide", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            val allFolders = root?.flattenAllFolders()?.sortedBy { it.path.lowercase() } ?: emptyList()
            items(allFolders, key = { it.path }) { folder ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        folder.path,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Checkbox(
                        checked = folder.path !in excludedFolders,
                        onCheckedChange = { checked -> viewModel.setFolderExcluded(folder.path, !checked) },
                    )
                    Checkbox(
                        checked = folder.path in hiddenFolders,
                        onCheckedChange = { checked -> viewModel.setFolderHidden(folder.path, checked) },
                    )
                }
            }
        }
    }
}
