package com.elghayesh.gallerybackup.ui.edit

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private enum class TrimMode { KEEP_SELECTION, REMOVE_SELECTION }

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoTrimScreen(item: MediaItem, viewModel: GalleryViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val durationMs = item.durationMs.coerceAtLeast(1000L)

    var trimMode by remember { mutableStateOf(TrimMode.KEEP_SELECTION) }
    var trimRange by remember { mutableStateOf(0f..durationMs.toFloat()) }
    // Where the preview scrubber (below the trim range) currently sits -- always kept inside
    // trimRange, separately from the range's own two handles, so the user can freely scrub
    // anywhere *within* the selected area to check its contents without disturbing the start/end
    // points they already set.
    var previewPositionMs by remember { mutableStateOf(0f) }
    // Playback speed baked into the exported file (not just this screen's own preview) -- 1x
    // leaves the export untouched (and eligible for the near-lossless trim path below); any other
    // value re-times both the video frames and, unless muted, the audio by the same factor so
    // they stay in sync.
    var editSpeed by remember { mutableStateOf(1f) }
    var muteAudio by remember { mutableStateOf(false) }
    // null while not saving; 0..100 while an export is running, so the overlay can show real
    // progress instead of an indeterminate spinner the user has no way to gauge the length of.
    var saveProgress by remember { mutableStateOf<Int?>(null) }
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
        saveProgress = 0
        scope.launch {
            val outputPath = File(context.cacheDir, "trim_${System.currentTimeMillis()}.mp4").absolutePath
            val success = transformVideo(
                context,
                item.uri,
                trimRange.start.toLong(),
                trimRange.endInclusive.toLong(),
                durationMs,
                removeSelection = trimMode == TrimMode.REMOVE_SELECTION,
                speed = editSpeed,
                muteAudio = muteAudio,
                outputPath,
                onProgress = { saveProgress = it },
            )
            if (success) {
                saveTrimmedVideo(context, outputPath, item, replace)
                if (replace) viewModel.deleteMediaItems(listOf(item), skipTrash = false)
                viewModel.refresh()
            }
            saveProgress = null
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

    // Removing the selection when it spans the whole video would leave nothing to save --
    // disable Save rather than silently produce (or fail to produce) an empty file.
    val removesEverything = trimMode == TrimMode.REMOVE_SELECTION &&
        trimRange.start <= 0f && trimRange.endInclusive >= durationMs.toFloat()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trim video") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel") }
                },
                actions = {
                    TextButton(
                        enabled = saveProgress == null && !removesEverything,
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
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = trimMode == TrimMode.KEEP_SELECTION,
                        onClick = { trimMode = TrimMode.KEEP_SELECTION },
                        label = { Text("Keep selection") },
                    )
                    FilterChip(
                        selected = trimMode == TrimMode.REMOVE_SELECTION,
                        onClick = { trimMode = TrimMode.REMOVE_SELECTION },
                        label = { Text("Remove selection") },
                    )
                }
                Text(
                    "Speed: ${formatSpeed(editSpeed)}",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = editSpeed,
                    onValueChange = { editSpeed = it },
                    valueRange = 0.25f..10f,
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !muteAudio,
                        onClick = { muteAudio = false },
                        label = { Text("Keep original audio") },
                    )
                    FilterChip(
                        selected = muteAudio,
                        onClick = { muteAudio = true },
                        label = { Text("Mute") },
                    )
                }
                val selectedDurationMs = (trimRange.endInclusive - trimRange.start).toLong()
                Text(
                    if (trimMode == TrimMode.KEEP_SELECTION) {
                        "Trim: ${formatMs(trimRange.start.toLong())} - ${formatMs(trimRange.endInclusive.toLong())}"
                    } else {
                        "Removing: ${formatMs(trimRange.start.toLong())} - ${formatMs(trimRange.endInclusive.toLong())}"
                    },
                )
                Text(
                    if (trimMode == TrimMode.KEEP_SELECTION) {
                        val atSpeedMs = (selectedDurationMs / editSpeed).toLong()
                        if (editSpeed == 1f) {
                            "Selected duration: ${formatMs(selectedDurationMs)}"
                        } else {
                            "Selected duration: ${formatMs(selectedDurationMs)} -- ${formatMs(atSpeedMs)} at ${formatSpeed(editSpeed)}"
                        }
                    } else if (removesEverything) {
                        "This would remove the entire video -- adjust the selection first."
                    } else {
                        val resultMs = durationMs - selectedDurationMs
                        val atSpeedMs = (resultMs / editSpeed).toLong()
                        if (editSpeed == 1f) {
                            "Result duration: ${formatMs(resultMs)}"
                        } else {
                            "Result duration: ${formatMs(resultMs)} -- ${formatMs(atSpeedMs)} at ${formatSpeed(editSpeed)}"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (removesEverything) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RangeSlider(
                    value = trimRange,
                    onValueChange = { newRange ->
                        // CLOSEST_SYNC seeks to the nearest keyframe instead of decoding forward
                        // from one to reach an exact frame -- that decode is what actually made
                        // dragging feel dead: an exact seek takes long enough that a fast drag's
                        // next seekTo() (fired on the very next onValueChange) supersedes it
                        // before a single frame finishes decoding, over and over, so nothing
                        // renders until the drag stops and the last seek is finally left alone
                        // long enough to complete. A keyframe-only seek has nothing to decode, so
                        // it keeps up with the drag instead.
                        exoPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC)
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
                    onValueChangeFinished = {
                        // Land exactly on the chosen frame once the drag settles -- CLOSEST_SYNC
                        // only approximated a nearby keyframe while dragging.
                        exoPlayer.setSeekParameters(SeekParameters.EXACT)
                        exoPlayer.seekTo(previewPositionMs.toLong())
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
                        exoPlayer.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                        exoPlayer.seekTo(position.toLong())
                    },
                    onValueChangeFinished = {
                        exoPlayer.setSeekParameters(SeekParameters.EXACT)
                        exoPlayer.seekTo(previewPositionMs.toLong())
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

private fun formatSpeed(speed: Float): String {
    if (speed == speed.toLong().toFloat()) return "${speed.toLong()}x"
    val trimmed = "%.2f".format(speed).trimEnd('0').trimEnd('.')
    return "${trimmed}x"
}

/**
 * Must run on a thread with a Looper (the caller's Main dispatcher) -- Transformer requires one.
 *
 * When [removeSelection] is false (keep the selection, the ordinary trim) AND [speed] is 1x, this
 * is a single, untouched clip and [experimentalSetTrimOptimizationEnabled] keeps it as close to
 * lossless as a cut can physically be: a video can only be split cleanly at a keyframe, so
 * Transformer re-encodes just the short group of pictures around the trim start (aligning it to
 * the nearest keyframe) and stream-copies -- byte for byte, same resolution, same bitrate, no
 * quality loss -- every frame after that.
 *
 * When [removeSelection] is true (cut the selection out, keep everything else), the output is
 * built from the two remaining pieces -- [0, startMs) and (endMs, totalDurationMs] -- concatenated
 * into one [EditedMediaItemSequence]. This can't get the same near-lossless treatment: trim
 * optimization only ever applies to a single clip (Transformer disables it automatically for a
 * multi-segment composition), since splicing two previously non-adjacent points together isn't a
 * straight byte copy the way a single cut's untouched remainder is -- the whole output is
 * re-encoded. That's an unavoidable consequence of removing a middle section, not a shortcut.
 *
 * A [speed] other than 1x always forces a full re-encode too, for both branches: every frame's
 * timestamp has to be rewritten ([SpeedChangeEffect]), which is exactly what the near-lossless
 * trim path above depends on NOT happening to any frame it stream-copies. [muteAudio] drops the
 * audio track entirely rather than just silencing it (silence would still be decoded, time-
 * stretched and re-encoded for nothing); otherwise the audio is time-stretched by the same factor
 * as the video via [SonicAudioProcessor] so picture and sound stay in sync, keeping its original
 * pitch rather than the chipmunk/slow-motion-voice effect a plain resample would give.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private suspend fun transformVideo(
    context: Context,
    sourceUri: Uri,
    startMs: Long,
    endMs: Long,
    totalDurationMs: Long,
    removeSelection: Boolean,
    speed: Float,
    muteAudio: Boolean,
    outputPath: String,
    onProgress: (Int) -> Unit,
): Boolean = coroutineScope {
    var transformerRef: Transformer? = null
    val progressJob = launch {
        val progressHolder = ProgressHolder()
        while (isActive) {
            delay(200)
            val transformer = transformerRef ?: continue
            if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                onProgress(progressHolder.progress)
            }
        }
    }
    try {
        suspendCancellableCoroutine { cont ->
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(true, onCancellation = null)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (cont.isActive) cont.resume(false, onCancellation = null)
                }
            }

            fun buildPiece(pieceStartMs: Long, pieceEndMs: Long): EditedMediaItem =
                EditedMediaItem.Builder(clippedMediaItem(sourceUri, pieceStartMs, pieceEndMs))
                    .setRemoveAudio(muteAudio)
                    .setEffects(speedEffects(speed, muteAudio))
                    .build()

            if (!removeSelection) {
                val editedMediaItem = buildPiece(startMs, endMs)
                val transformerBuilder = Transformer.Builder(context).addListener(listener)
                if (speed == 1f) transformerBuilder.experimentalSetTrimOptimizationEnabled(true)
                val transformer = transformerBuilder.build()
                transformerRef = transformer
                cont.invokeOnCancellation { transformer.cancel() }
                transformer.start(editedMediaItem, outputPath)
            } else {
                val pieces = buildList {
                    if (startMs > 0) add(buildPiece(0, startMs))
                    if (endMs < totalDurationMs) add(buildPiece(endMs, totalDurationMs))
                }
                if (pieces.isEmpty()) {
                    cont.resume(false, onCancellation = null)
                    return@suspendCancellableCoroutine
                }
                val composition = Composition.Builder(EditedMediaItemSequence(pieces)).build()
                val transformer = Transformer.Builder(context).addListener(listener).build()
                transformerRef = transformer
                cont.invokeOnCancellation { transformer.cancel() }
                transformer.start(composition, outputPath)
            }
        }
    } finally {
        progressJob.cancel()
    }
}

private fun clippedMediaItem(sourceUri: Uri, startMs: Long, endMs: Long): ExoMediaItem =
    ExoMediaItem.Builder()
        .setUri(sourceUri)
        .setClippingConfiguration(
            ExoMediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build(),
        )
        .build()

/**
 * [SpeedChangeEffect] re-times the video frames; [SonicAudioProcessor.setSpeed] re-times the
 * audio by the same factor (its pitch stays at the class default of 1x, so speeding up or slowing
 * down doesn't chipmunk or drawl the audio) so picture and sound stay in sync. Skipped entirely at
 * 1x (a no-op that would otherwise still force a full re-encode) and whenever [muteAudio] drops
 * the audio track anyway.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun speedEffects(speed: Float, muteAudio: Boolean): Effects {
    val videoEffects = if (speed != 1f) listOf(SpeedChangeEffect(speed)) else emptyList()
    val audioProcessors = if (speed != 1f && !muteAudio) {
        listOf(SonicAudioProcessor().apply { setSpeed(speed) })
    } else {
        emptyList()
    }
    return Effects(audioProcessors, videoEffects)
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
            // Without these, a freshly-inserted MediaStore row defaults its dates to "now" -- the
            // trimmed video would look like a brand-new file instead of keeping the original's.
            put(MediaStore.Video.Media.DATE_TAKEN, original.dateTakenSec * 1000)
            put(MediaStore.Video.Media.DATE_MODIFIED, original.dateModifiedSec)
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
