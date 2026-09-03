package com.elghayesh.gallerybackup.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.elghayesh.gallerybackup.data.media.allFilesAccessSettingsIntent
import com.elghayesh.gallerybackup.data.media.hasAllFilesAccess

/**
 * A banner shown only while "All files access" isn't granted, offering to grant it. Calls
 * [onGranted] once (on first composition if already granted, or right after returning from the
 * settings screen with it newly granted) so the caller can (re)load whatever needed the
 * permission -- there's no broadcast for this, so re-checking when the settings screen returns
 * control is the only way to notice a grant.
 */
@Composable
fun AllFilesAccessPrompt(onGranted: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasAllFilesAccess()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        granted = hasAllFilesAccess()
    }
    LaunchedEffect(granted) { if (granted) onGranted() }

    if (!granted) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("See every folder", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Grant \"All files access\" to browse every folder on your device here, not " +
                        "just ones that currently have photos or videos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { launcher.launch(allFilesAccessSettingsIntent(context)) }) {
                Text("Grant")
            }
        }
    }
}
