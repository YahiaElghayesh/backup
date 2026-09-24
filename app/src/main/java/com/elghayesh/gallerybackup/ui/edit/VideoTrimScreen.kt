package com.elghayesh.gallerybackup.ui.edit

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoTrimScreen(item: MediaItem, viewModel: GalleryViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val durationMs = item.durationMs.coerceAtLeast(1000L)

    var trimRange by remember { mutableStateOf(0f..durationMs.toFloat()) }
    // Where the preview scrubber (below the trim range) currently sits -- always kept inside
    // trimRange, separately from the range's own two handles, so the user can freely scrub
    // anywhere *within* the selected area to check its contents without disturbing the start/end
    // points they already set.
    var previewPositionMs by remember { mutableStateOf(0f) }
    var isSaving by remember { mutableStateOf(false) }
    var showSaveChoiceDialog by remember { mutableStateOf(false) }

    val exoPlayer = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(item.uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    // Keeps the preview scrubber in sync with actual playback position, and loops playback back
    // to the trim start the moment it reaches the trim end -- so pressing play previews exactly
    // the selected area, on repeat, instead of running past it into the part being cut.
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            if (exoPlayer.isPlaying) {
                val position = exoPlayer.currentPosition.toFloat()
                if (position >= trimRange.endInclusive) {
                    exoPlayer.seekTo(trimRange.start.toLong())
                    previewPositionMs = trimRange.start
                } else {
                    previewPositionMs = position.coerceIn(trimRange.start, trimRange.endInclusive)
                }
            }
            delay(100)
        }
    }

    fun performSave(replace: Boolean) {
        isSaving = true
        scope.launch {
            val outputPath = File(context.cacheDir, "trim_${System.currentTimeMillis()}.mp4").absolutePath
            val success = transformVideo(
                context,
                item.uri,
                trimRange.start.toLong(),
                trimRange.endInclusive.toLong(),
                outputPath,
            )
            if (success) {
                saveTrimmedVideo(context, outputPath, item, replace)
                if (replace) viewModel.deleteMediaItems(listOf(item), skipTrash = false)
                viewModel.refresh()
            }
            isSaving = false
            onDone()
        }
    }

    if (showSaveChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showSaveChoiceDialog = false },
            title = { Text("Save changes") },
            text = { Text("Replace the original video, or save your trim as a new file alongside it?") },
            confirmButton = {
                TextButton(onClick = { showSaveChoiceDialog = false; performSave(replace = true) }) {
                    Text("Replace original")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveChoiceDialog = false; performSave(replace = false) }) {
                    Text("Save as new")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trim video") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel") }
                },
                actions = {
                    TextButton(
                        enabled = !isSaving,
                        onClick = { showSaveChoiceDialog = true },
                    ) {
                        Text("Save")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (isSaving) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Trim: ${formatMs(trimRange.start.toLong())} - ${formatMs(trimRange.endInclusive.toLong())}")
                Text(
                    "Selected duration: ${formatMs((trimRange.endInclusive - trimRange.start).toLong())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RangeSlider(
                    value = trimRange,
                    onValueChange = { newRange ->
                        // Seek the player live to whichever handle actually moved, so the preview
                        // shows the exact frame at that point while it's being dragged -- rather
                        // than only updating after the user lifts their finger.
                        val movedStart = newRange.start != trimRange.start
                        val movedEnd = newRange.endInclusive != trimRange.endInclusive
                        trimRange = newRange
                        when {
                            movedStart -> {
                                exoPlayer.seekTo(newRange.start.toLong())
                                previewPositionMs = newRange.start
                            }
                            movedEnd -> {
                                exoPlayer.seekTo(newRange.endInclusive.toLong())
                                previewPositionMs = newRange.endInclusive
                            }
                        }
                        previewPositionMs = previewPositionMs.coerceIn(newRange.start, newRange.endInclusive)
                    },
                    valueRange = 0f..durationMs.toFloat(),
                )
                Text(
                    "Preview within selection: ${formatMs(previewPositionMs.toLong())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // A separate scrubber spanning only the selected area -- lets the user freely
                // move through everything they've selected (not just its two endpoints) to
                // confirm nothing they want got left out, without that drag also moving the trim
                // start/end above.
                Slider(
                    value = previewPositionMs.coerceIn(trimRange.start, trimRange.endInclusive),
                    onValueChange = { position ->
                        previewPositionMs = position
                        exoPlayer.seekTo(position.toLong())
                    },
                    valueRange = trimRange,
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/**
 * Must run on a thread with a Looper (the caller's Main dispatcher) -- Transformer requires one.
 *
 * [experimentalSetTrimOptimizationEnabled] is what keeps the trim as close to lossless as a cut
 * can physically be: a video can only be split cleanly at a keyframe, so Transformer re-encodes
 * just the short group of pictures around the trim start (aligning it to the nearest keyframe)
 * and stream-copies -- byte for byte, same resolution, same bitrate, no quality loss -- every
 * frame after that. Without it, Transformer re-encodes the entire clip from scratch, which is
 * exactly the quality/resolution drift trimming a video should never cause.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private suspend fun transformVideo(
    context: Context,
    sourceUri: Uri,
    startMs: Long,
    endMs: Long,
    outputPath: String,
): Boolean = suspendCancellableCoroutine { cont ->
    val mediaItem = ExoMediaItem.Builder()
        .setUri(sourceUri)
        .setClippingConfiguration(
            ExoMediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build(),
        )
        .build()
    val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

    val transformer = Transformer.Builder(context)
        .experimentalSetTrimOptimizationEnabled(true)
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
    transformer.start(editedMediaItem, outputPath)
}

private suspend fun saveTrimmedVideo(context: Context, outputPath: String, original: MediaItem, replace: Boolean) =
    withContext(Dispatchers.IO) {
        val tempFile = File(outputPath)
        if (!tempFile.exists()) return@withContext

        val baseName = original.displayName.substringBeforeLast('.', original.displayName)
        val fileName = if (replace) "$baseName.mp4" else "${baseName}_trimmed_${System.currentTimeMillis() / 1000}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    if (original.folderPath.isEmpty()) "Movies" else original.folderPath,
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
