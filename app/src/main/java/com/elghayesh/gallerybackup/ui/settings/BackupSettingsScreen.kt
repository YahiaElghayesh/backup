package com.elghayesh.gallerybackup.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elghayesh.gallerybackup.data.media.flattenAllFolders
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    galleryViewModel: GalleryViewModel,
    backupViewModel: BackupViewModel,
    onBack: () -> Unit,
) {
    val root by galleryViewModel.root.collectAsState()
    val selectedFolders by backupViewModel.selectedFolders.collectAsState(initial = emptySet())
    val wifiOnly by backupViewModel.wifiOnly.collectAsState(initial = true)
    val isConnected by backupViewModel.isConnected.collectAsState(initial = false)
    val lastSyncTime by backupViewModel.lastSyncTime.collectAsState(initial = null)
    val lastSyncStatus by backupViewModel.lastSyncStatus.collectAsState(initial = null)
    val statusMessage by backupViewModel.statusMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            backupViewModel.clearStatusMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxWidth()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Google Drive connection", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (isConnected) "Connected" else "Not connected",
                        color = if (isConnected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (isConnected) {
                        OutlinedButton(onClick = { backupViewModel.disconnect() }) {
                            Text("Disconnect")
                        }
                    } else {
                        Button(onClick = { backupViewModel.connectToDrive() }) {
                            Text("Connect Google Drive")
                        }
                    }
                }
                HorizontalDivider()
            }

            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Folders to back up", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Each folder is independent -- checking a folder does not automatically " +
                            "include its subfolders. Check exactly the ones you want, at any level.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val allFolders = root?.flattenAllFolders()?.sortedBy { it.path.lowercase() } ?: emptyList()
            items(allFolders, key = { it.path }) { folder ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = selectedFolders.contains(folder.path),
                        onCheckedChange = { checked -> backupViewModel.toggleFolder(folder.path, checked) },
                    )
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(folder.path, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${folder.items.size} items directly inside",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Wi-Fi only", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Skip uploads on mobile data",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = wifiOnly, onCheckedChange = { backupViewModel.setWifiOnly(it) })
                }
                HorizontalDivider()
            }

            item {
                Column(Modifier.padding(16.dp)) {
                    Button(
                        onClick = { backupViewModel.backupNow() },
                        enabled = isConnected && selectedFolders.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Back up now")
                    }
                    Spacer(Modifier.height(6.dp))
                    val syncTime = lastSyncTime
                    val syncText = if (syncTime == null) {
                        "Never synced yet."
                    } else {
                        val formatted = DateFormat.getDateTimeInstance().format(Date(syncTime))
                        "Last sync: $formatted -- ${lastSyncStatus ?: ""}"
                    }
                    Text(
                        syncText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
