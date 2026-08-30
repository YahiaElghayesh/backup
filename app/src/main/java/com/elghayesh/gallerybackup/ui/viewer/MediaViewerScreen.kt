package com.elghayesh.gallerybackup.ui.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.data.media.findNode
import com.elghayesh.gallerybackup.ui.common.FolderPickerDialog
import com.elghayesh.gallerybackup.ui.common.MediaActionBar
import com.elghayesh.gallerybackup.ui.common.PropertiesDialog
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
    val rawRoot by viewModel.root.collectAsState()
    val hiddenMediaIds by viewModel.hiddenMediaIds.collectAsState()
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
    var currentScale by remember { mutableFloatStateOf(1f) }

    var transferMode by remember { mutableStateOf<ViewerTransferMode?>(null) }
    var showProperties by remember { mutableStateOf(false) }
    val requestDelete = rememberDeleteRequester(viewModel)

    val currentItem = media.getOrNull(pagerState.currentPage)

    transferMode?.let { mode ->
        FolderPickerDialog(
            root = rawRoot,
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
                        onMoveTo = { transferMode = ViewerTransferMode.MOVE },
                        onCopyTo = { transferMode = ViewerTransferMode.COPY },
                        onProperties = { showProperties = true },
                        hideLabel = if (isHidden) "Unhide" else "Hide",
                        onToggleHidden = { viewModel.setMediaHidden(item.id, !isHidden) },
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
            ZoomableMediaBox(
                onScaleChanged = { if (isCurrent) currentScale = it },
            ) {
                if (item.isVideo && isCurrent) {
                    VideoPlayer(item)
                } else {
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

/** Pinch to zoom (up to 8x) and drag to pan once zoomed. Only intercepts single-finger drags
 * once already zoomed in, so swiping between photos at normal (1x) zoom is unaffected. */
@Composable
private fun ZoomableMediaBox(
    onScaleChanged: (Float) -> Unit,
    content: @Composable () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.size
                        if (pointerCount >= 2 || scale > 1f) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                val newScale = (scale * zoomChange).coerceIn(1f, 8f)
                                scale = newScale
                                offset = if (newScale > 1f) offset + panChange else Offset.Zero
                                onScaleChanged(newScale)
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPlayer(item: MediaItem) {
    val context = LocalContext.current
    val exoPlayer = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(item.uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
