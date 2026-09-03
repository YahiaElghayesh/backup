package com.elghayesh.gallerybackup.ui.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.elghayesh.gallerybackup.data.update.ReleaseInfo
import com.elghayesh.gallerybackup.data.update.UpdateChecker
import com.elghayesh.gallerybackup.data.update.UpdateCheckCoordinator
import kotlinx.coroutines.launch
import java.io.File

/**
 * There's no Play Store for this app, so it checks its own GitHub Release on launch and, if a
 * newer CI build is out, offers to download and install it in place -- mount this once near the
 * root of the Compose tree so it isn't tied to any particular screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdateController() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checker = remember { UpdateChecker(context.applicationContext) }

    var availableUpdate by remember { mutableStateOf<ReleaseInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var needsInstallPermission by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val file = downloadedFile
        if (file != null && checker.canInstallUnknownApps()) {
            checker.installApk(file)
            availableUpdate = null
        }
    }

    suspend fun performCheck() {
        UpdateCheckCoordinator.onCheckStarted()
        val update = checker.checkForUpdate()
        if (update != null) availableUpdate = update
        UpdateCheckCoordinator.onCheckFinished(foundUpdate = update != null)
    }

    LaunchedEffect(Unit) { performCheck() }
    LaunchedEffect(Unit) {
        UpdateCheckCoordinator.requests.collect { performCheck() }
    }

    availableUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = { if (!downloading) availableUpdate = null },
            title = { Text("Update available") },
            text = {
                Column {
                    Text("Build #${update.versionCode} is ready to install.")
                    downloadError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it)
                    }
                    if (downloading) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !downloading,
                    onClick = {
                        downloadError = null
                        downloading = true
                        scope.launch {
                            try {
                                val file = checker.downloadApk(update.downloadUrl) { p -> progress = p }
                                downloadedFile = file
                                if (checker.canInstallUnknownApps()) {
                                    checker.installApk(file)
                                    availableUpdate = null
                                } else {
                                    needsInstallPermission = true
                                }
                            } catch (e: Exception) {
                                downloadError = "Download failed: ${e.message ?: "unknown error"}"
                            } finally {
                                downloading = false
                            }
                        }
                    },
                ) {
                    Text(if (downloading) "Downloading..." else "Update now")
                }
            },
            dismissButton = {
                TextButton(enabled = !downloading, onClick = { availableUpdate = null }) {
                    Text("Later")
                }
            },
        )
    }

    if (needsInstallPermission) {
        AlertDialog(
            onDismissRequest = { needsInstallPermission = false },
            title = { Text("Allow installing updates") },
            text = {
                Text(
                    "MediaHub needs permission to install the update it just downloaded. " +
                        "Turn on \"Allow from this source\" on the next screen, then come back here.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    needsInstallPermission = false
                    settingsLauncher.launch(checker.unknownAppsSettingsIntent())
                }) {
                    Text("Open settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { needsInstallPermission = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
