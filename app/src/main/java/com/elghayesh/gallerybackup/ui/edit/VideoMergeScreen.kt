package com.elghayesh.gallerybackup.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

/**
 * Picks the merge order for a set of already-selected videos (arrow buttons rather than a drag
 * gesture -- no reorderable-list library is in this project, and up/down buttons are simple
 * enough to get right without needing to test a custom drag gesture on-device) and joins them,
 * in that order, into one new video file alongside the originals -- the originals themselves are
 * left untouched, so there's no "replace" choice the way trimming has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoMergeScreen(items: List<MediaItem>, viewModel: GalleryViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var orderedItems by remember(items) { mutableStateOf(items) }
    // null while not saving; 0..100 while an export is running.
    var saveProgress by remember { mutableStateOf<Int?>(null) }

    fun moveItem(index: Int, delta: Int) {
        val target = index + delta
        if (target !in orderedItems.indices) return
        orderedItems = orderedItems.toMutableList().apply {
            val moved = removeAt(index)
            add(target, moved)
        }
    }

    fun removeItem(index: Int) {
        orderedItems = orderedItems.toMutableList().apply { removeAt(index) }
    }

    // Blocks leaving mid-save via the back button/gesture -- not via minimizing the app. A merge
    // never touches its source videos (no "replace" choice), so unlike VideoTrimScreen there's no
    // consent-dialog step this is protecting; it's here purely so the screen (and its progress
    // overlay) doesn't just vanish out from under an in-progress save the user is watching.
    BackHandler(enabled = saveProgress != null) {}

    fun performMerge() {
        if (orderedItems.size < 2) return
        saveProgress = 0
        val referenceItem = orderedItems.first()
        val workId = VideoExportWorker.enqueueMerge(
            context = context,
            uris = orderedItems.map { it.uri },
            firstItemDisplayName = referenceItem.displayName,
            firstItemFolderPath = referenceItem.folderPath,
            firstItemDateTakenSec = referenceItem.dateTakenSec,
            firstItemDateModifiedSec = referenceItem.dateModifiedSec,
        )
        scope.launch {
            // Runs as a foreground-service-backed WorkManager job (see VideoExportWorker) so it
            // keeps going even if this screen -- and the app along with it -- gets backgrounded;
            // this loop just reflects its progress back into the UI while it's still around to.
            WorkManager.getInstance(context).getWorkInfoByIdFlow(workId).collect { info ->
                // saveProgress == null is also this collector's own "already handled completion"
                // guard -- the flow can go on emitting the same finished WorkInfo again (e.g. once
                // WorkManager prunes it), and onDone() must run at most once.
                if (info == null || saveProgress == null) return@collect
                if (info.state.isFinished) {
                    if (info.state == WorkInfo.State.SUCCEEDED) viewModel.refresh()
                    saveProgress = null
                    onDone()
                } else {
                    saveProgress = info.progress.getInt(VideoExportWorker.KEY_PROGRESS, saveProgress ?: 0)
                }
            }
        }
    }

    val totalDurationMs = orderedItems.sumOf { it.durationMs }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Merge videos") },
                navigationIcon = {
                    IconButton(onClick = onDone, enabled = saveProgress == null) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(
                        enabled = saveProgress == null && orderedItems.size >= 2,
                        onClick = { performMerge() },
                    ) {
                        Text("Merge")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "${orderedItems.size} videos -- ${formatMergeMs(totalDurationMs)} total once merged",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(orderedItems, key = { _, item -> item.id }) { index, item ->
                        MergeItemRow(
                            item = item,
                            position = index + 1,
                            canMoveUp = index > 0,
                            canMoveDown = index < orderedItems.lastIndex,
                            canRemove = orderedItems.size > 2,
                            onMoveUp = { moveItem(index, -1) },
                            onMoveDown = { moveItem(index, 1) },
                            onRemove = { removeItem(index) },
                        )
                    }
                }
            }
            saveProgress?.let { progress ->
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(progress = { progress / 100f })
                        Text(
                            "$progress%",
                            modifier = Modifier.padding(top = 8.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "You can minimize the app -- this will keep saving in the background.",
                            modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MergeItemRow(
    item: MediaItem,
    position: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$position",
            modifier = Modifier.width(24.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatMergeMs(item.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
        }
        IconButton(onClick = onRemove, enabled = canRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Remove from merge")
        }
    }
}

private fun formatMergeMs(ms: Long): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
