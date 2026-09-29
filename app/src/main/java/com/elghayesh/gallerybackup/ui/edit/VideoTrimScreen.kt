package com.elghayesh.gallerybackup.ui.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class TrimMode { KEEP_SELECTION, REMOVE_SELECTION }

/** Which trim handle the frame-step buttons currently move. */
private enum class TrimHandle { START, END }

/**
 * The secondary controls below the always-visible timeline are grouped behind these tabs, the way
 * most dedicated video editors (CapCut, InShot, Google Photos' own editor) keep a big canvas and a
 * single compact tool panel at a time instead of stacking every control on screen at once -- only
 * one group is ever showing below the timeline, and each one is kept to at most two short rows on
 * purpose so nothing here ever needs to scroll.
 */
private enum class TrimTab { TRIM, SPEED, AUDIO, ROTATE, CROP }

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoTrimScreen(item: MediaItem, viewModel: GalleryViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val durationMs = item.durationMs.coerceAtLeast(1000L)

    var trimMode by remember { mutableStateOf(TrimMode.KEEP_SELECTION) }
    var trimRange by remember { mutableStateOf(0f..durationMs.toFloat()) }
    var previewPositionMs by remember { mutableStateOf(0f) }
    // Playback speed baked into the exported file (not just this screen's own preview) -- 1x
    // leaves the export untouched (and eligible for the near-lossless trim path below); any other
    // value re-times both the video frames and, unless muted, the audio by the same factor so
    // they stay in sync.
    var editSpeed by remember { mutableStateOf(1f) }
    var muteAudio by remember { mutableStateOf(false) }
    // Clockwise, one of 0/90/180/270 -- baked into the export via ScaleAndRotateTransformation and
    // applied live to the preview via graphicsLayer.rotationZ (also clockwise-positive), so what's
    // shown while editing matches what gets saved.
    var rotationDegrees by remember { mutableStateOf(0) }
    // Relative to the frame AS ROTATED by rotationDegrees above, not the source video's own
    // orientation -- see cropEffectFor's doc comment in VideoExport.kt for why that ordering
    // matters at export time; it's what keeps this consistent with what the Crop tab's overlay is
    // actually drawn on top of.
    var cropRect by remember { mutableStateOf(NormRect.FULL) }
    var cropAspect by remember { mutableStateOf(CropAspect.FREE) }
    // null while not saving; 0..100 while an export is running, so the overlay can show real
    // progress instead of an indeterminate spinner the user has no way to gauge the length of.
    var saveProgress by remember { mutableStateOf<Int?>(null) }
    var showSaveChoiceDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(TrimTab.TRIM) }

    // Which handle the frame-step buttons in the Trim tab move, and the video's own frame rate and
    // pixel size (kept updated from the player itself once its format loads, in the position-
    // polling effect below) -- frame rate is needed to know how many milliseconds "one frame"
    // actually is (not fixed across videos), and the pixel size to fit/letterbox the preview and to
    // aspect-lock the Crop tab correctly. Both fall back to plausible defaults until the real
    // values load.
    var adjustingHandle by remember { mutableStateOf(TrimHandle.START) }
    var frameRateFps by remember { mutableStateOf(30f) }
    var videoWidth by remember { mutableStateOf(16) }
    var videoHeight by remember { mutableStateOf(9) }
    var isSavingFrame by remember { mutableStateOf(false) }
    var frameSavedMessage by remember { mutableStateOf<String?>(null) }
    // Drives the play/pause overlay -- ExoPlayer's own stock transport controls (play/pause plus
    // skip-back-10s/skip-forward-10s) are turned off entirely (useController = false below) since
    // they sat permanently on top of the preview and duplicated the frame-accurate trim controls
    // this screen already has; this is the minimal replacement, a single icon that only appears
    // while paused.
    var isPlaying by remember { mutableStateOf(false) }
    // The actual on-screen size (post rotation-fit) of the video content area -- what the Crop
    // tab's drag handles are positioned/scaled against, exactly like CropOverlay already is for a
    // photo's own bitmap box in PhotoEditScreen.
    var videoBoxSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(frameSavedMessage) {
        if (frameSavedMessage != null) {
            delay(2000)
            frameSavedMessage = null
        }
    }

    val exoPlayer = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(item.uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    // Applies the chosen export speed/mute to the LIVE preview too, not just the file that gets
    // written out on Save -- without this, the speed slider visibly did nothing while previewing,
    // even though it was already correctly baked into the export. PlaybackParameters only
    // time-stretches (pitch stays natural) rather than resampling, matching the SonicAudioProcessor
    // behavior applied at export time, so the preview's audio pitch matches what gets saved too.
    LaunchedEffect(exoPlayer, editSpeed, muteAudio) {
        exoPlayer.setPlaybackParameters(PlaybackParameters(editSpeed))
        exoPlayer.volume = if (muteAudio) 0f else 1f
    }

    // Keeps the preview scrubber in sync with actual playback position and the play/pause overlay
    // in sync with the player's real state, and loops playback back to the trim start the moment
    // it reaches the trim end -- so pressing play previews exactly the selected area, on repeat,
    // instead of running past it into the part being cut.
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            // Neither read below is gated on isPlaying (unlike the seek logic further down) -- the
            // format/video size is available as soon as the player has read the file, whether or
            // not it's actually playing.
            exoPlayer.videoFormat?.frameRate?.let { fps -> if (fps > 0f) frameRateFps = fps }
            val size = exoPlayer.videoSize
            if (size.width > 0 && size.height > 0) {
                videoWidth = size.width
                videoHeight = size.height
            }
            isPlaying = exoPlayer.isPlaying
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

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.currentPosition.toFloat() >= trimRange.endInclusive) {
                exoPlayer.seekTo(trimRange.start.toLong())
            }
            exoPlayer.play()
        }
    }

    // A crop drawn relative to one rotation no longer lines up once the frame is rotated further,
    // so rotating (like PhotoEditScreen's own Rotate 90 button) starts the crop selection fresh
    // rather than leaving a now-mismatched rect in place.
    fun rotate(deltaClockwise: Int) {
        rotationDegrees = ((rotationDegrees + deltaClockwise) % 360 + 360) % 360
        cropRect = NormRect.FULL
        cropAspect = CropAspect.FREE
    }

    // Moves whichever handle is selected above by exactly one frame -- an exact (not
    // CLOSEST_SYNC) seek, since this is a single deliberate step rather than a fast drag, and
    // landing on the precise requested frame is the entire point of a frame-step control.
    fun stepFrame(direction: Int) {
        val frameMs = 1000f / frameRateFps
        val delta = direction * frameMs
        val newRange = when (adjustingHandle) {
            TrimHandle.START -> {
                val maxStart = (trimRange.endInclusive - frameMs).coerceAtLeast(0f)
                (trimRange.start + delta).coerceIn(0f, maxStart)..trimRange.endInclusive
            }
            TrimHandle.END -> {
                val minEnd = (trimRange.start + frameMs).coerceAtMost(durationMs.toFloat())
                trimRange.start..(trimRange.endInclusive + delta).coerceIn(minEnd, durationMs.toFloat())
            }
        }
        trimRange = newRange
        val seekTarget = if (adjustingHandle == TrimHandle.START) newRange.start else newRange.endInclusive
        exoPlayer.setSeekParameters(SeekParameters.EXACT)
        exoPlayer.seekTo(seekTarget.toLong())
        previewPositionMs = seekTarget.coerceIn(newRange.start, newRange.endInclusive)
    }

    fun performSaveFrame() {
        if (isSavingFrame) return
        isSavingFrame = true
        scope.launch {
            val savedName = saveFrameAsPhoto(
                context = context,
                sourceUri = item.uri,
                atMs = previewPositionMs.toLong(),
                displayName = item.displayName,
                folderPath = item.folderPath,
                dateTakenSec = item.dateTakenSec,
                dateModifiedSec = item.dateModifiedSec,
            )
            if (savedName != null) viewModel.refresh()
            frameSavedMessage = if (savedName != null) "Saved as $savedName" else "Couldn't save this frame"
            isSavingFrame = false
        }
    }

    // Blocks leaving mid-save via the back button/gesture -- not via minimizing the app, which
    // this whole feature is meant to allow. The export itself runs in VideoExportWorker regardless
    // of whether this screen stays around to see it finish, but "replace original" still needs a
    // live Activity for the trash confirmation dialog (see VideoExportWorker's own doc comment),
    // which only the code below -- once it observes the export finish -- runs. Navigating away
    // early would skip that step, leaving the original never trashed.
    BackHandler(enabled = saveProgress != null) {}

    fun performSave(replace: Boolean) {
        saveProgress = 0
        val workId = VideoExportWorker.enqueueTrim(
            context = context,
            sourceUri = item.uri,
            startMs = trimRange.start.toLong(),
            endMs = trimRange.endInclusive.toLong(),
            totalDurationMs = durationMs,
            removeSelection = trimMode == TrimMode.REMOVE_SELECTION,
            speed = editSpeed,
            muteAudio = muteAudio,
            rotationDegrees = rotationDegrees,
            cropRect = cropRect,
            replace = replace,
            originalDisplayName = item.displayName,
            originalFolderPath = item.folderPath,
            originalDateTakenSec = item.dateTakenSec,
            originalDateModifiedSec = item.dateModifiedSec,
        )
        scope.launch {
            // Runs as a foreground-service-backed WorkManager job (see VideoExportWorker) so it
            // keeps going even if this screen -- and the app along with it -- gets backgrounded;
            // this loop just reflects its progress back into the UI while it's still around to.
            WorkManager.getInstance(context).getWorkInfoByIdFlow(workId).collect { info ->
                // saveProgress == null is also this collector's own "already handled completion"
                // guard -- the flow can go on emitting the same finished WorkInfo again (e.g. once
                // WorkManager prunes it), and onDone()/deleteMediaItems must run at most once.
                if (info == null || saveProgress == null) return@collect
                if (info.state.isFinished) {
                    if (info.state == WorkInfo.State.SUCCEEDED && replace) {
                        viewModel.deleteMediaItems(listOf(item), skipTrash = false)
                    }
                    viewModel.refresh()
                    saveProgress = null
                    onDone()
                } else {
                    saveProgress = info.progress.getInt(VideoExportWorker.KEY_PROGRESS, saveProgress ?: 0)
                }
            }
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

    val selectedDurationMs = (trimRange.endInclusive - trimRange.start).toLong()
    val summaryText = when {
        trimMode == TrimMode.KEEP_SELECTION && editSpeed == 1f ->
            "${formatMs(trimRange.start.toLong())} - ${formatMs(trimRange.endInclusive.toLong())}  ·  " +
                formatMs(selectedDurationMs)
        trimMode == TrimMode.KEEP_SELECTION ->
            "${formatMs(trimRange.start.toLong())} - ${formatMs(trimRange.endInclusive.toLong())}  ·  " +
                "${formatMs(selectedDurationMs)} → ${formatMs((selectedDurationMs / editSpeed).toLong())} " +
                "at ${formatSpeed(editSpeed)}"
        removesEverything -> "Removing the entire video -- adjust the selection first"
        else -> {
            val resultMs = durationMs - selectedDurationMs
            val removing = "Removing ${formatMs(trimRange.start.toLong())} - ${formatMs(trimRange.endInclusive.toLong())}"
            if (editSpeed == 1f) {
                "$removing  ·  result ${formatMs(resultMs)}"
            } else {
                "$removing  ·  result ${formatMs(resultMs)} → ${formatMs((resultMs / editSpeed).toLong())} " +
                    "at ${formatSpeed(editSpeed)}"
            }
        }
    }

    // The video's own natural size, swapped when a 90/270 rotation is applied -- what the preview
    // is fit/letterboxed against and what the Crop tab's aspect lock is computed against, so both
    // reflect what's actually displayed rather than the source file's un-rotated orientation.
    val rotated = rotationDegrees % 180 != 0
    val displayedVideoWidth = if (rotated) videoHeight else videoWidth
    val displayedVideoHeight = if (rotated) videoWidth else videoHeight

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trim video") },
                navigationIcon = {
                    IconButton(onClick = onDone, enabled = saveProgress == null) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { performSaveFrame() },
                        enabled = !isSavingFrame && saveProgress == null,
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = "Save this frame as a photo")
                    }
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
            // The preview: as big as the screen allows, since everything below it is capped to
            // only what its currently-selected tab needs (see TrimTab above), and each of those is
            // kept to at most two short rows so nothing here ever needs to scroll.
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val displayAspect = displayedVideoWidth.toFloat() / displayedVideoHeight.toFloat()
                val boxWidthDp: androidx.compose.ui.unit.Dp
                val boxHeightDp: androidx.compose.ui.unit.Dp
                if (maxWidth / displayAspect <= maxHeight) {
                    boxWidthDp = maxWidth
                    boxHeightDp = maxWidth / displayAspect
                } else {
                    boxHeightDp = maxHeight
                    boxWidthDp = maxHeight * displayAspect
                }
                // The AndroidView itself is sized to the UN-rotated aspect (the box dimensions
                // swapped back) and then visually rotated onto the box above via graphicsLayer --
                // rotating a WxH view by 90 degrees makes its on-screen footprint HxW, which is
                // exactly boxWidthDp x boxHeightDp by construction, so it lines up without any
                // stretching or clipping.
                val viewWidthDp = if (rotated) boxHeightDp else boxWidthDp
                val viewHeightDp = if (rotated) boxWidthDp else boxHeightDp
                Box(
                    Modifier
                        .size(boxWidthDp, boxHeightDp)
                        .onSizeChanged { videoBoxSize = it },
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                // Stock ExoPlayer transport controls (play/pause, skip -10s/+10s)
                                // are replaced by the single tap-to-toggle layer below -- they used
                                // to sit permanently on top of the video and duplicated the frame-
                                // accurate scrubbing controls this screen already has underneath
                                // the preview.
                                useController = false
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(viewWidthDp, viewHeightDp)
                            .graphicsLayer { rotationZ = rotationDegrees.toFloat() },
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = saveProgress == null && !isSavingFrame,
                            ) { togglePlayPause() },
                    )
                    if (!isPlaying && saveProgress == null && !isSavingFrame) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .size(64.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                        }
                    }
                    if (selectedTab == TrimTab.CROP) {
                        CropOverlay(
                            rect = cropRect,
                            boxSize = videoBoxSize,
                            density = density,
                            onCornerDrag = { corner, dxNorm, dyNorm ->
                                var rect = updatedCropRect(cropRect, corner, dxNorm, dyNorm)
                                if (cropAspect != CropAspect.FREE) {
                                    rect = applyAspectLock(rect, cropAspect.ratio, displayedVideoWidth, displayedVideoHeight)
                                }
                                cropRect = rect
                            },
                            onMoveDrag = { dxNorm, dyNorm -> cropRect = translatedRect(cropRect, dxNorm, dyNorm) },
                            onDragEnd = {},
                        )
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
                    if (isSavingFrame) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    frameSavedMessage?.let { message ->
                        Box(
                            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                message,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.small)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            // The timeline: always visible right under the preview, the way a trim/scrub bar
            // always is in dedicated video editors -- this is the one control group that stays on
            // screen regardless of which tab below is selected.
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                Text(
                    summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (removesEverything) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
            }

            // The tool panel: an icon tab row plus whichever one group of secondary controls is
            // currently selected -- everything that isn't the timeline above lives behind one of
            // these tabs, each deliberately kept to at most two short rows so this panel never
            // needs to scroll.
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                TrimTabButton(
                    icon = Icons.Filled.ContentCut,
                    label = "Trim",
                    selected = selectedTab == TrimTab.TRIM,
                    onClick = { selectedTab = TrimTab.TRIM },
                    modifier = Modifier.weight(1f),
                )
                TrimTabButton(
                    icon = Icons.Filled.Speed,
                    label = "Speed",
                    selected = selectedTab == TrimTab.SPEED,
                    onClick = { selectedTab = TrimTab.SPEED },
                    modifier = Modifier.weight(1f),
                )
                TrimTabButton(
                    icon = Icons.Filled.VolumeUp,
                    label = "Audio",
                    selected = selectedTab == TrimTab.AUDIO,
                    onClick = { selectedTab = TrimTab.AUDIO },
                    modifier = Modifier.weight(1f),
                )
                TrimTabButton(
                    icon = Icons.Filled.RotateRight,
                    label = "Rotate",
                    selected = selectedTab == TrimTab.ROTATE,
                    onClick = { selectedTab = TrimTab.ROTATE },
                    modifier = Modifier.weight(1f),
                )
                TrimTabButton(
                    icon = Icons.Filled.Crop,
                    label = "Crop",
                    selected = selectedTab == TrimTab.CROP,
                    onClick = { selectedTab = TrimTab.CROP },
                    modifier = Modifier.weight(1f),
                )
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                when (selectedTab) {
                    TrimTab.TRIM -> {
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
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { stepFrame(-1) }) {
                                Icon(Icons.Filled.NavigateBefore, contentDescription = "Previous frame")
                            }
                            // Tapping the label itself switches which handle the frame-step
                            // buttons move -- folds the old separate "Adjust: Start/End" chip row
                            // into this one, so the whole Trim tab fits in two rows instead of
                            // three and never needs to scroll.
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        adjustingHandle = if (adjustingHandle == TrimHandle.START) TrimHandle.END else TrimHandle.START
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    "${if (adjustingHandle == TrimHandle.START) "Start" else "End"}: " +
                                        formatMsPrecise(
                                            if (adjustingHandle == TrimHandle.START) trimRange.start.toLong() else trimRange.endInclusive.toLong(),
                                        ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { stepFrame(1) }) {
                                Icon(Icons.Filled.NavigateNext, contentDescription = "Next frame")
                            }
                        }
                    }
                    TrimTab.SPEED -> {
                        Text(
                            "Speed: ${formatSpeed(editSpeed)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = editSpeed,
                            onValueChange = { editSpeed = it },
                            valueRange = 0.05f..20f,
                        )
                    }
                    TrimTab.AUDIO -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    }
                    TrimTab.ROTATE -> {
                        // A single button, always +90° clockwise -- matches the photo editor's own
                        // Rotate 90° button exactly (RotateStraightenControls in PhotoEditScreen.kt),
                        // rather than introducing a separate "rotate left" concept nothing else in
                        // this app has.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { rotate(90) }) {
                                Icon(Icons.Filled.RotateRight, contentDescription = "Rotate 90°")
                            }
                            Text(
                                "$rotationDegrees°",
                                modifier = Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    TrimTab.CROP -> {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CropAspect.entries.forEach { aspect ->
                                    FilterChip(
                                        selected = cropAspect == aspect,
                                        onClick = {
                                            cropAspect = aspect
                                            cropRect = if (aspect == CropAspect.FREE) {
                                                NormRect.FULL
                                            } else {
                                                applyAspectLock(cropRect, aspect.ratio, displayedVideoWidth, displayedVideoHeight)
                                            }
                                        },
                                        label = { Text(aspect.label) },
                                    )
                                }
                            }
                            if (cropRect != NormRect.FULL) {
                                IconButton(onClick = { cropRect = NormRect.FULL; cropAspect = CropAspect.FREE }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Reset crop")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrimTabButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/** Tenths-of-a-second precision -- formatMs alone can't show a single frame-step actually moving
 * anything, since one frame is usually well under a tenth of a second's own rounding either way. */
private fun formatMsPrecise(ms: Long): String {
    val clamped = ms.coerceAtLeast(0)
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(clamped)
    val tenths = (clamped % 1000) / 100
    return "%d:%02d.%d".format(totalSec / 60, totalSec % 60, tenths)
}

private fun formatSpeed(speed: Float): String {
    if (speed == speed.toLong().toFloat()) return "${speed.toLong()}x"
    val trimmed = "%.2f".format(speed).trimEnd('0').trimEnd('.')
    return "${trimmed}x"
}
