package com.elghayesh.gallerybackup.ui.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import com.elghayesh.gallerybackup.R
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.data.media.findNode
import com.elghayesh.gallerybackup.ui.common.FolderTreePickerDialog
import com.elghayesh.gallerybackup.ui.common.MediaActionBar
import com.elghayesh.gallerybackup.ui.common.PropertiesDialog
import com.elghayesh.gallerybackup.ui.common.RenameDialog
import com.elghayesh.gallerybackup.ui.common.rememberDeleteRequester
import com.elghayesh.gallerybackup.ui.common.shareMedia
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel

private enum class ViewerTransferMode { MOVE, COPY }

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MediaViewerScreen(
    path: String,
    startIndex: Int,
    viewModel: GalleryViewModel,
    onEditPhoto: (path: String, index: Int) -> Unit,
    onEditVideo: (path: String, index: Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // Must match the same (filtered) list GalleryScreen computed indices from -- otherwise
    // an index picked from the visible grid could resolve to a different, hidden item here.
    val visibleRoot by viewModel.visibleRoot.collectAsState()
    val hiddenMediaIds by viewModel.hiddenMediaIds.collectAsState()
    val favoriteMediaIds by viewModel.favoriteMediaIds.collectAsState()
    val media = remember(visibleRoot, path) {
        visibleRoot?.findNode(path)?.items?.sortedByDescending { it.dateModifiedSec } ?: emptyList()
    }

    if (media.isEmpty()) {
        onBack()
        return
    }

    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, media.lastIndex),
        pageCount = { media.size },
    )
    val pagerScope = rememberCoroutineScope()
    var currentScale by remember { mutableFloatStateOf(1f) }

    var transferMode by remember { mutableStateOf<ViewerTransferMode?>(null) }
    var showProperties by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    val requestDelete = rememberDeleteRequester(viewModel)

    val currentItem = media.getOrNull(pagerState.currentPage)

    if (showRenameDialog && currentItem != null) {
        RenameDialog(
            title = "Rename",
            initialName = currentItem.displayName.substringBeforeLast('.', currentItem.displayName),
            onConfirm = { newName ->
                viewModel.renameMediaItem(currentItem, newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }
    transferMode?.let { mode ->
        FolderTreePickerDialog(
            viewModel = viewModel,
            root = visibleRoot,
            title = if (mode == ViewerTransferMode.MOVE) "Move to..." else "Copy to...",
            onPick = { destination ->
                currentItem?.let { item ->
                    if (mode == ViewerTransferMode.MOVE) {
                        viewModel.moveMediaItems(listOf(item), destination)
                    } else {
                        viewModel.copyMediaItems(listOf(item), destination)
                    }
                }
                transferMode = null
            },
            onDismiss = { transferMode = null },
        )
    }
    if (showProperties && currentItem != null) {
        PropertiesDialog(items = listOf(currentItem), onDismiss = { showProperties = false })
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(currentItem?.displayName.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            val item = currentItem
            if (item != null) {
                val isHidden = item.id in hiddenMediaIds
                BottomAppBar(containerColor = Color.Black) {
                    MediaActionBar(
                        onEdit = {
                            if (item.isVideo) onEditVideo(path, pagerState.currentPage) else onEditPhoto(path, pagerState.currentPage)
                        },
                        onShare = { shareMedia(context, listOf(item)) },
                        onDelete = { requestDelete(listOf(item)) },
                        isFavorite = item.id in favoriteMediaIds,
                        onToggleFavorite = { viewModel.setMediaFavorite(item.id, item.id !in favoriteMediaIds) },
                        overflowActions = listOf(
                            (if (isHidden) "Unhide" else "Hide") to { viewModel.setMediaHidden(item.id, !isHidden) },
                            "Rename" to { showRenameDialog = true },
                            "Move to..." to { transferMode = ViewerTransferMode.MOVE },
                            "Copy to..." to { transferMode = ViewerTransferMode.COPY },
                            "Properties" to { showProperties = true },
                        ),
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = currentScale <= 1f,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        ) { page ->
            val item = media.getOrNull(page) ?: return@HorizontalPager
            val isCurrent = pagerState.currentPage == page
            // Panning is only useful up to the zoomed content's own edge; dragging further in
            // that direction past the edge instead advances to the next/previous item, the same
            // as an unzoomed swipe would, rather than being stuck once the pager's own swipe is
            // disabled by being zoomed in.
            val onSwipeNext: () -> Unit = {
                currentScale = 1f
                if (page < media.lastIndex) pagerScope.launch { pagerState.animateScrollToPage(page + 1) }
            }
            val onSwipePrevious: () -> Unit = {
                currentScale = 1f
                if (page > 0) pagerScope.launch { pagerState.animateScrollToPage(page - 1) }
            }
            if (item.isVideo && isCurrent) {
                // The controls (play/pause, skip, progress bar) are a sibling drawn on top of the
                // zoomed content, not inside it -- so pinching/panning the video doesn't also
                // scale or move them. ZoomableMediaBox reports plain taps back via onTap so this
                // overlay's visibility can be driven by the same gesture that handles pinch/pan.
                val videoState = rememberVideoPlayerState(item)
                Box(Modifier.fillMaxSize()) {
                    ZoomableMediaBox(
                        onScaleChanged = { currentScale = it },
                        onTap = { videoState.controlsVisible = !videoState.controlsVisible },
                        onSwipeNext = onSwipeNext,
                        onSwipePrevious = onSwipePrevious,
                    ) {
                        VideoSurface(videoState.exoPlayer, modifier = Modifier.fillMaxSize())
                    }
                    VideoControlsOverlay(videoState)
                }
            } else {
                ZoomableMediaBox(
                    onScaleChanged = { if (isCurrent) currentScale = it },
                    onSwipeNext = onSwipeNext,
                    onSwipePrevious = onSwipePrevious,
                ) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** Pinch to zoom (up to 8x) and drag to pan once zoomed, plus double-tap to toggle between
 * 1x and 3x. Only intercepts single-finger drags once already zoomed in, so swiping between
 * photos at normal (1x) zoom is unaffected. Panning is clamped to the zoomed content's own edge;
 * continuing to drag past that edge instead calls [onSwipeNext]/[onSwipePrevious], the same as an
 * unzoomed swipe would -- otherwise there'd be no way to move to the next item without first
 * zooming back out, since the pager's own swipe is disabled while zoomed in. */
@Composable
private fun ZoomableMediaBox(
    onScaleChanged: (Float) -> Unit,
    onTap: () -> Unit = {},
    onSwipeNext: () -> Unit = {},
    onSwipePrevious: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                var lastTapUpTimeMs = 0L
                var lastTapPosition = Offset.Zero
                val tapSlopPx = 24.dp.toPx()
                val swipeThresholdPx = 72.dp.toPx()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var totalPan = 0f
                    var edgeOverscrollX = 0f
                    var lastEvent: PointerEvent
                    do {
                        val event = awaitPointerEvent()
                        lastEvent = event
                        val pointerCount = event.changes.size
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        totalPan += panChange.getDistance()
                        if (pointerCount >= 2 || scale > 1f) {
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                val newScale = (scale * zoomChange).coerceIn(1f, 8f)
                                scale = newScale
                                if (newScale > 1f) {
                                    val maxOffsetX = boxSize.width * (newScale - 1f) / 2f
                                    val maxOffsetY = boxSize.height * (newScale - 1f) / 2f
                                    val unclampedX = offset.x + panChange.x
                                    val clampedX = unclampedX.coerceIn(-maxOffsetX, maxOffsetX)
                                    val clampedY = (offset.y + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                                    val overscrollX = unclampedX - clampedX
                                    // Only a single-finger drag pinned at the edge counts -- reset
                                    // as soon as it isn't (pinch gesture, or panning within bounds).
                                    edgeOverscrollX = if (pointerCount == 1 && overscrollX != 0f) {
                                        edgeOverscrollX + overscrollX
                                    } else {
                                        0f
                                    }
                                    offset = Offset(clampedX, clampedY)
                                } else {
                                    offset = Offset.Zero
                                    edgeOverscrollX = 0f
                                }
                                onScaleChanged(newScale)
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            }
                        } else {
                            edgeOverscrollX = 0f
                        }
                    } while (event.changes.any { it.pressed })

                    if (edgeOverscrollX <= -swipeThresholdPx) {
                        onSwipeNext()
                    } else if (edgeOverscrollX >= swipeThresholdPx) {
                        onSwipePrevious()
                    } else if (totalPan < tapSlopPx && lastEvent.changes.size == 1) {
                        val upPosition = lastEvent.changes.first().position
                        val now = System.currentTimeMillis()
                        val isDoubleTap = now - lastTapUpTimeMs < 300 &&
                            (upPosition - lastTapPosition).getDistance() < tapSlopPx * 3
                        onTap()
                        if (isDoubleTap) {
                            scale = if (scale > 1f) 1f else 3f
                            offset = Offset.Zero
                            onScaleChanged(scale)
                            lastTapUpTimeMs = 0L
                        } else {
                            lastTapUpTimeMs = now
                            lastTapPosition = upPosition
                        }
                    }
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Playback state shared between [VideoSurface] and [VideoControlsOverlay] -- split apart so the
 * overlay can be drawn outside the pinch-zoom transform while still driving the same player. */
private class VideoPlayerState(val exoPlayer: ExoPlayer) {
    var controlsVisible by mutableStateOf(false)
    var isPlaying by mutableStateOf(true)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var isScrubbing by mutableStateOf(false)
}

@Composable
private fun rememberVideoPlayerState(item: MediaItem): VideoPlayerState {
    val context = LocalContext.current
    val exoPlayer = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(item.uri))
            prepare()
            playWhenReady = true
        }
    }
    val state = remember(exoPlayer) { VideoPlayerState(exoPlayer) }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    LaunchedEffect(exoPlayer) {
        while (true) {
            if (!state.isScrubbing) {
                state.positionMs = exoPlayer.currentPosition.coerceAtLeast(0)
                state.durationMs = exoPlayer.duration.coerceAtLeast(0)
            }
            state.isPlaying = exoPlayer.isPlaying
            delay(300)
        }
    }
    return state
}

/**
 * Just the raw video surface -- meant to sit inside [ZoomableMediaBox] so pinch-zoom/pan applies
 * to the picture itself. Uses a TextureView (via a plain layout resource) rather than Media3's
 * default SurfaceView: a SurfaceView is its own OS-composited layer positioned by absolute screen
 * coordinates, which doesn't reliably stay lined up with a parent that recomposes inside a pager
 * and is scaled/translated by a graphicsLayer transform -- it can end up rendered shrunk and
 * mispositioned. TextureView draws as a normal View layer, so it always follows Compose's
 * measured bounds.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoSurface(exoPlayer: ExoPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            (android.view.LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, null) as PlayerView).apply {
                player = exoPlayer
            }
        },
        modifier = modifier,
    )
}

/**
 * The play/pause/skip buttons and progress bar, drawn as a sibling on top of the zoomed video
 * content (not inside it) so pinching/panning the video never scales or moves the controls.
 * [VideoPlayerState.controlsVisible] is toggled from [ZoomableMediaBox]'s tap handler.
 */
@Composable
private fun VideoControlsOverlay(state: VideoPlayerState) {
    val exoPlayer = state.exoPlayer
    Box(Modifier.fillMaxSize()) {
        if (state.controlsVisible) {
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerControlButton(Icons.Filled.Replay10, "Rewind 10 seconds") {
                    exoPlayer.seekTo((exoPlayer.currentPosition - 10_000).coerceAtLeast(0))
                }
                PlayerControlButton(
                    icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    size = 72.dp,
                ) {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                }
                PlayerControlButton(Icons.Filled.Forward10, "Forward 10 seconds") {
                    exoPlayer.seekTo((exoPlayer.currentPosition + 10_000).coerceAtMost(exoPlayer.duration.coerceAtLeast(0)))
                }
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatVideoTime(state.positionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Slider(
                value = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { fraction ->
                    state.isScrubbing = true
                    state.positionMs = (fraction * state.durationMs).toLong()
                },
                onValueChangeFinished = {
                    exoPlayer.seekTo(state.positionMs)
                    state.isScrubbing = false
                },
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.White,
                    thumbColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(formatVideoTime(state.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PlayerControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = Color.White,
        modifier = Modifier
            .padding(12.dp)
            .size(size)
            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(size / 5),
    )
}

private fun formatVideoTime(ms: Long): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
