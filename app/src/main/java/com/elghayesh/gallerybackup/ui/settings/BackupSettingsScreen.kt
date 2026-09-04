package com.elghayesh.gallerybackup.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    backupViewModel: BackupViewModel,
    onOpenFolderExplorer: () -> Unit,
    onBack: () -> Unit,
) {
    val selectedFolders by backupViewModel.selectedFolders.collectAsState(initial = emptySet())
    val wifiOnly by backupViewModel.wifiOnly.collectAsState(initial = true)
    val isConnected by backupViewModel.isConnected.collectAsState(initial = false)
    val lastSyncTime by backupViewModel.lastSyncTime.collectAsState(initial = null)
    val lastSyncStatus by backupViewModel.lastSyncStatus.collectAsState(initial = null)
    val statusMessage by backupViewModel.statusMessage.collectAsState()

    val oneDriveClientId by backupViewModel.oneDriveClientId.collectAsState(initial = "")
    val oneDriveEnabled by backupViewModel.oneDriveEnabled.collectAsState(initial = false)
    val oneDriveConnected by backupViewModel.oneDriveConnected.collectAsState(initial = false)
    val oneDriveLastSyncTime by backupViewModel.oneDriveLastSyncTime.collectAsState(initial = null)
    val oneDriveLastSyncStatus by backupViewModel.oneDriveLastSyncStatus.collectAsState(initial = null)
    var clientIdInput by remember(oneDriveClientId) { mutableStateOf(oneDriveClientId) }

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
                    Text("OneDrive connection", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (oneDriveConnected) "Connected" else "Not connected",
                        color = if (oneDriveConnected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (oneDriveConnected) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = oneDriveEnabled, onCheckedChange = { backupViewModel.setOneDriveEnabled(it) })
                            Spacer(Modifier.width(8.dp))
                            Text("Back up to OneDrive too")
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = { backupViewModel.disconnectOneDrive() }) {
                            Text("Disconnect")
                        }
                    } else {
                        Text(
                            "Needs an Azure app registration's client id (\"Mobile and desktop " +
                                "applications\" platform, redirect URI mediahub://oauth/onedrive, " +
                                "Files.ReadWrite + offline_access delegated permissions).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = clientIdInput,
                            onValueChange = { clientIdInput = it },
                            label = { Text("Azure app client id") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { backupViewModel.connectToOneDrive(clientIdInput) },
                            enabled = clientIdInput.isNotBlank(),
                        ) {
                            Text("Connect OneDrive")
                        }
                    }
                    if (oneDriveConnected) {
                        val syncTime = oneDriveLastSyncTime
                        val syncText = if (syncTime == null) {
                            "Never synced yet."
                        } else {
                            "Last sync: ${DateFormat.getDateTimeInstance().format(Date(syncTime))} -- ${oneDriveLastSyncStatus ?: ""}"
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(syncText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
                        Text("Folders to back up", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (selectedFolders.isEmpty()) {
                                "No folders selected yet -- tap to browse, file-explorer style."
                            } else {
                                "${selectedFolders.size} folder(s) selected. Checking a folder backs up " +
                                    "everything inside it, including its subfolders."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
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
                        enabled = (isConnected || (oneDriveConnected && oneDriveEnabled)) && selectedFolders.isNotEmpty(),
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
