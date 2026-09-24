package com.elghayesh.gallerybackup.ui.edit

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
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
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import coil.compose.AsyncImage
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Picks the merge order for a set of already-selected videos (arrow buttons rather than a drag
 * gesture -- no reorderable-list library is in this project, and up/down buttons are simple
 * enough to get right without needing to test a custom drag gesture on-device) and joins them,
 * in that order, into one new video file alongside the originals -- the originals themselves are
 * left untouched, so there's no "replace" choice the way trimming has.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoMergeScreen(items: List<MediaItem>, viewModel: GalleryViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var orderedItems by remember(items) { mutableStateOf(items) }
    var isSaving by remember { mutableStateOf(false) }

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

    fun performMerge() {
        if (orderedItems.size < 2) return
        isSaving = true
        scope.launch {
            val outputPath = File(context.cacheDir, "merge_${System.currentTimeMillis()}.mp4").absolutePath
            val success = mergeVideos(context, orderedItems.map { it.uri }, outputPath)
            if (success) {
                saveMergedVideo(context, outputPath, orderedItems.first())
                viewModel.refresh()
            }
            isSaving = false
            onDone()
        }
    }

    val totalDurationMs = orderedItems.sumOf { it.durationMs }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Merge videos") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel") }
                },
                actions = {
                    TextButton(
                        enabled = !isSaving && orderedItems.size >= 2,
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
            if (isSaving) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
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

/**
 * Must run on a thread with a Looper (the caller's Main dispatcher) -- Transformer requires one.
 *
 * Joins [uris], in that exact order, into a single [EditedMediaItemSequence]. Like Remove-selection
 * trimming, this is a multi-segment composition -- Transformer's trim optimization never applies
 * to one, so the whole output is re-encoded; there's no way around that when combining separate
 * source files into one.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private suspend fun mergeVideos(
    context: Context,
    uris: List<android.net.Uri>,
    outputPath: String,
): Boolean = suspendCancellableCoroutine { cont ->
    val pieces = uris.map { EditedMediaItem.Builder(ExoMediaItem.fromUri(it)).build() }
    val composition = Composition.Builder(EditedMediaItemSequence(pieces)).build()

    val transformer = Transformer.Builder(context)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (cont.isActive) cont.resume(true, onCancellation = null)
            }

            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                if (cont.isActive) cont.resume(false, onCancellation = null)
            }
        })
        .build()

    cont.invokeOnCancellation { transformer.cancel() }
    transformer.start(composition, outputPath)
}

private suspend fun saveMergedVideo(context: Context, outputPath: String, referenceItem: MediaItem) =
    withContext(Dispatchers.IO) {
        val tempFile = File(outputPath)
        if (!tempFile.exists()) return@withContext

        val fileName = "merged_${System.currentTimeMillis() / 1000}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    referenceItem.folderPath.ifEmpty { "Movies" },
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out -> tempFile.inputStream().use { it.copyTo(out) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val doneValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, doneValues, null, null)
            }
        }
        tempFile.delete()
    }
