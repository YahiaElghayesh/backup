package com.elghayesh.gallerybackup.ui.collage

import androidx.activity.compose.BackHandler
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.data.settings.AccentColor
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One photo's placement within the collage, all normalized 0..1 of the canvas's own size
 * (can go slightly negative or past 1 while being resized/moved via the arrow controls --
 * clamped generously rather than tightly, so a photo can be positioned right up to, or slightly
 * past, the canvas edge). */
private data class CollageCell(
    val itemIndex: Int,
    val xNorm: Float,
    val yNorm: Float,
    val wNorm: Float,
    val hNorm: Float,
)

private fun rowLayout(n: Int): List<CollageCell> {
    val w = 1f / n
    return (0 until n).map { i -> CollageCell(i, i * w, 0f, w, 1f) }
}

private fun columnLayout(n: Int): List<CollageCell> {
    val h = 1f / n
    return (0 until n).map { i -> CollageCell(i, 0f, i * h, 1f, h) }
}

private fun gridLayout(n: Int): List<CollageCell> {
    val cols = ceil(sqrt(n.toFloat())).toInt().coerceAtLeast(1)
    val rows = ceil(n.toFloat() / cols).toInt().coerceAtLeast(1)
    val cellW = 1f / cols
    val cellH = 1f / rows
    return (0 until n).map { i ->
        val col = i % cols
        val row = i / cols
        CollageCell(i, col * cellW, row * cellH, cellW, cellH)
    }
}

/**
 * A row of cells sized to each photo's own [aspectRatios] instead of uniform fractions -- every
 * cell shares the full canvas height, and its width is proportional to how wide that photo is
 * relative to its height. Combined with a non-cropping [ContentScale.Fit]/letterbox-free draw
 * (each cell's own aspect exactly matches its photo, minus the border inset), this is what makes
 * "Fit" pack photos edge to edge with nothing cut off and no wasted background space.
 */
private fun fitRowLayout(aspectRatios: List<Float>): List<CollageCell> {
    val safeAspects = aspectRatios.map { it.takeIf { a -> a > 0f && a.isFinite() } ?: 1f }
    val total = safeAspects.sum().takeIf { it > 0f } ?: 1f
    var x = 0f
    return safeAspects.mapIndexed { i, aspect ->
        val w = aspect / total
        val cell = CollageCell(i, x, 0f, w, 1f)
        x += w
        cell
    }
}

/** Column counterpart of [fitRowLayout]: every cell shares the full canvas width, and its height
 * is proportional to how tall that photo is relative to its width. */
private fun fitColumnLayout(aspectRatios: List<Float>): List<CollageCell> {
    val safeAspects = aspectRatios.map { it.takeIf { a -> a > 0f && a.isFinite() } ?: 1f }
    val inverses = safeAspects.map { 1f / it }
    val total = inverses.sum().takeIf { it > 0f } ?: 1f
    var y = 0f
    return inverses.mapIndexed { i, inv ->
        val h = inv / total
        val cell = CollageCell(i, 0f, y, 1f, h)
        y += h
        cell
    }
}

/** [fitRowLayout]'s overall width:height ratio at unit height -- used to size the canvas itself
 * (see [fitColumnRatio] for the column counterpart). */
private fun fitRowRatio(aspectRatios: List<Float>): Float =
    aspectRatios.map { it.takeIf { a -> a > 0f && a.isFinite() } ?: 1f }.sum().coerceAtLeast(0.01f)

/** [fitColumnLayout]'s overall width:height ratio at unit width. */
private fun fitColumnRatio(aspectRatios: List<Float>): Float {
    val totalInverseHeight = aspectRatios.sumOf { a ->
        val safe = a.takeIf { it > 0f && it.isFinite() } ?: 1f
        (1.0 / safe)
    }.toFloat().coerceAtLeast(0.01f)
    return 1f / totalInverseHeight
}

private enum class CollagePreset(val label: String, val build: (Int) -> List<CollageCell>) {
    GRID("Grid", ::gridLayout),
    ROW("Row", ::rowLayout),
    COLUMN("Column", ::columnLayout),
}

/** [ratio] null means "Free" -- the canvas's own width:height comes from independently adjustable
 * slider values instead of a fixed preset. */
private enum class CanvasAspectPreset(val label: String, val ratio: Float?) {
    SQUARE("1:1", 1f),
    FOUR_THREE("4:3", 4f / 3f),
    THREE_FOUR("3:4", 3f / 4f),
    SIXTEEN_NINE("16:9", 16f / 9f),
    NINE_SIXTEEN("9:16", 9f / 16f),
    A4_PORTRAIT("A4", 210f / 297f),
    A4_LANDSCAPE("A4 ↻", 297f / 210f),
    FREE("Free", null),
}

/**
 * Builds a collage from 2+ selected photos: pick a starting layout preset (or leave the default),
 * select a photo to reveal arrow controls for repositioning/resizing it (no freehand dragging),
 * and either pick a canvas shape (a fixed ratio, a page size, or fully free width/height) or --
 * for Row/Column layouts -- turn on Fit, which sizes the canvas itself to exactly wrap the photos
 * at their own proportions with nothing cropped and no wasted space. Border width and background
 * color are shared across every gap between/around photos. Pinch to zoom the preview itself to
 * check detail before saving. Every photo is always drawn in full (never cropped) -- see
 * [saveCollage]'s own doc comment for how that's reconciled with photos that don't share their
 * cell's exact aspect ratio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollageScreen(
    items: List<MediaItem>,
    viewModel: GalleryViewModel,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val photos = remember(items) { items.filter { !it.isVideo } }

    if (photos.size < 2) {
        Scaffold(
            containerColor = Color.Black,
            topBar = {
                TopAppBar(
                    title = { Text("Collage", color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Select at least 2 photos to make a collage.", color = Color.White.copy(alpha = 0.7f))
            }
        }
        return
    }

    var activePreset by remember { mutableStateOf(CollagePreset.GRID) }
    var cells by remember { mutableStateOf(CollagePreset.GRID.build(photos.size)) }
    var selectedItemIndex by remember { mutableStateOf<Int?>(null) }
    var canvasAspectPreset by remember { mutableStateOf(CanvasAspectPreset.SQUARE) }
    // Whether the canvas size is derived from the photos' own proportions (Row/Column only --
    // see fitRowLayout/fitColumnLayout) instead of a chosen aspect/free size. Turned off whenever
    // the layout switches to Grid, since Fit has no defined meaning for a 2D grid of mismatched
    // photo shapes.
    var fitToContent by remember { mutableStateOf(false) }
    var freeWidthText by remember { mutableStateOf("4") }
    var freeHeightText by remember { mutableStateOf("3") }
    var borderWidthDp by remember { mutableStateOf(10f) }
    var backgroundColorSeed by remember { mutableStateOf(AccentColor.WHITE.seed) }
    var isSaving by remember { mutableStateOf(false) }
    var showDeleteOriginalsPrompt by remember { mutableStateOf(false) }

    // Without this, system back closed the whole collage editor immediately even with a photo
    // selected -- one back press now deselects it first, matching the same graduated behavior
    // added to the photo editor.
    BackHandler {
        if (selectedItemIndex != null) selectedItemIndex = null else onDone()
    }

    val bitmaps = remember { mutableStateMapOf<Long, Bitmap>() }
    LaunchedEffect(photos) {
        for (photo in photos) {
            if (bitmaps.containsKey(photo.id)) continue
            // Downsampled -- this is only ever used for the live, on-screen preview (drag speed,
            // memory while several photos are open on this screen at once); the actual saved
            // output always re-decodes every photo at (up to) full resolution separately, see
            // saveCollage's own doc comment.
            loadDownsampledBitmap(context, photo.uri, maxDimension = 1600)?.let { bitmaps[photo.id] = it }
        }
    }

    // No upper limit on what can be typed here -- any positive number is a valid ratio.
    val freeWidthValue = freeWidthText.toFloatOrNull()?.takeIf { it > 0f } ?: 1f
    val freeHeightValue = freeHeightText.toFloatOrNull()?.takeIf { it > 0f } ?: 1f

    // Real aspect ratio of each photo as currently loaded (falls back to 1:1 for one not yet
    // decoded) -- downsampling via inSampleSize preserves a bitmap's aspect ratio essentially
    // exactly, so this matches what the full-resolution decode at save time will compute too.
    val photoAspects = photos.map { photo -> bitmaps[photo.id]?.let { it.width.toFloat() / it.height.toFloat() } }
    val fitReady = fitToContent && photoAspects.all { it != null }

    val effectiveCells: List<CollageCell>
    val effectiveCanvasRatio: Float
    if (fitReady) {
        val aspects = photoAspects.map { it!! }
        if (activePreset == CollagePreset.COLUMN) {
            effectiveCells = fitColumnLayout(aspects)
            effectiveCanvasRatio = fitColumnRatio(aspects)
        } else {
            effectiveCells = fitRowLayout(aspects)
            effectiveCanvasRatio = fitRowRatio(aspects)
        }
    } else {
        effectiveCells = cells
        effectiveCanvasRatio = canvasAspectPreset.ratio ?: (freeWidthValue / freeHeightValue)
    }

    fun moveCell(itemIndex: Int, dxNorm: Float, dyNorm: Float) {
        cells = cells.map {
            if (it.itemIndex == itemIndex) {
                it.copy(
                    xNorm = (it.xNorm + dxNorm).coerceIn(-0.5f, 1f),
                    yNorm = (it.yNorm + dyNorm).coerceIn(-0.5f, 1f),
                )
            } else {
                it
            }
        }
    }

    fun resizeCell(itemIndex: Int, dwNorm: Float, dhNorm: Float) {
        cells = cells.map {
            if (it.itemIndex == itemIndex) {
                it.copy(
                    wNorm = (it.wNorm + dwNorm).coerceIn(0.08f, 1.5f),
                    hNorm = (it.hNorm + dhNorm).coerceIn(0.08f, 1.5f),
                )
            } else {
                it
            }
        }
    }

    fun performSave() {
        if (isSaving) return
        isSaving = true
        val snapshotCells = cells
        val snapshotRatio = canvasAspectPreset.ratio ?: (freeWidthValue / freeHeightValue)
        val snapshotFitLayout = if (fitToContent) activePreset else null
        val snapshotBorder = borderWidthDp
        val snapshotBackground = backgroundColorSeed
        // Same folder as the source photos rather than a fixed "Collages" folder -- the first
        // selected photo's own folder, since a collage combining photos from different folders
        // has no single obviously-correct destination.
        val folderPath = photos.first().folderPath
        scope.launch {
            saveCollage(
                context,
                photos,
                snapshotCells,
                snapshotFitLayout,
                snapshotRatio,
                snapshotBorder,
                snapshotBackground,
                folderPath,
            )
            viewModel.refresh()
            isSaving = false
            showDeleteOriginalsPrompt = true
        }
    }

    val panelBg = Color(0xFF1C1C1C)

    if (showDeleteOriginalsPrompt) {
        AlertDialog(
            onDismissRequest = { showDeleteOriginalsPrompt = false; onDone() },
            title = { Text("Delete original photos?") },
            text = { Text("Collage saved. Delete the ${photos.size} original photos used to make it?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMediaItems(photos, skipTrash = false)
                    showDeleteOriginalsPrompt = false
                    onDone()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteOriginalsPrompt = false; onDone() }) { Text("Keep") }
            },
        )
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("Collage (${photos.size})", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White) }
                },
                actions = {
                    TextButton(enabled = !isSaving, onClick = { performSave() }) {
                        Text("Save", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .background(panelBg)
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
            ) {
                CollageSectionLabel("Layout", modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CollagePreset.entries.forEach { preset ->
                        CollagePill(
                            label = preset.label,
                            selected = activePreset == preset,
                            onClick = {
                                activePreset = preset
                                cells = preset.build(photos.size)
                                selectedItemIndex = null
                                if (preset == CollagePreset.GRID) fitToContent = false
                            },
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Color.White.copy(alpha = 0.12f))
                Spacer(Modifier.height(16.dp))

                CollageSectionLabel("Canvas shape", modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (activePreset != CollagePreset.GRID) {
                        CollagePill(label = "Fit", selected = fitToContent, onClick = { fitToContent = true })
                    }
                    CanvasAspectPreset.entries.forEach { a ->
                        CollagePill(
                            label = a.label,
                            selected = !fitToContent && canvasAspectPreset == a,
                            onClick = { fitToContent = false; canvasAspectPreset = a },
                        )
                    }
                }
                if (fitToContent) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Canvas size automatically fits your photos -- nothing is cropped.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (!fitToContent && canvasAspectPreset == CanvasAspectPreset.FREE) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val fieldColors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
                            cursorColor = Color.White,
                        )
                        OutlinedTextField(
                            value = freeWidthText,
                            onValueChange = { freeWidthText = it },
                            label = { Text("Width") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = fieldColors,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = freeHeightText,
                            onValueChange = { freeHeightText = it },
                            label = { Text("Height") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = fieldColors,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Color.White.copy(alpha = 0.12f))
                Spacer(Modifier.height(16.dp))

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CollageSectionLabel("Border", modifier = Modifier.weight(1f))
                    Text(
                        "${borderWidthDp.roundToInt()}dp",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Slider(
                    value = borderWidthDp,
                    onValueChange = { borderWidthDp = it },
                    valueRange = 0f..300f,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Color.White.copy(alpha = 0.12f))
                Spacer(Modifier.height(16.dp))

                CollageSectionLabel("Background color", modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AccentColor.entries.forEach { color ->
                        val isSelected = backgroundColorSeed == color.seed
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(color.seed))
                                .border(width = if (isSelected) 3.dp else 0.dp, color = Color.White, shape = CircleShape)
                                .clickable { backgroundColorSeed = color.seed },
                        )
                    }
                }
                if (selectedItemIndex != null && !fitToContent) {
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Color.White.copy(alpha = 0.12f))
                    Spacer(Modifier.height(16.dp))
                    CollageSectionLabel("Selected photo", modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val step = 0.04f
                        val selIndex = selectedItemIndex!!
                        CollageIconButton(Icons.Filled.Remove, "Zoom out") { resizeCell(selIndex, -step, -step) }
                        CollageIconButton(Icons.Filled.Add, "Zoom in") { resizeCell(selIndex, step, step) }
                        Spacer(Modifier.weight(1f))
                        CollageIconButton(Icons.Filled.KeyboardArrowLeft, "Move left") { moveCell(selIndex, -step, 0f) }
                        CollageIconButton(Icons.Filled.KeyboardArrowUp, "Move up") { moveCell(selIndex, 0f, -step) }
                        CollageIconButton(Icons.Filled.KeyboardArrowDown, "Move down") { moveCell(selIndex, 0f, step) }
                        CollageIconButton(Icons.Filled.KeyboardArrowRight, "Move right") { moveCell(selIndex, step, 0f) }
                    }
                }
            }
        },
    ) { padding ->
        var previewScale by remember { mutableFloatStateOf(1f) }
        var previewOffset by remember { mutableStateOf(Offset.Zero) }
        var previewBoxSize by remember { mutableStateOf(IntSize.Zero) }

        BoxWithConstraints(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { previewBoxSize = it }
                // Pinch to zoom the whole collage preview (up to 6x) and drag to pan once zoomed,
                // so detail can be checked before saving. Only intercepts once actually zoomed in
                // or a second finger is down -- a plain single-finger tap/drag at 1x is left
                // untouched, so selecting a photo and the canvas's own tap-to-deselect still work
                // exactly as before. Same gesture-tracking shape as MediaViewerScreen's own
                // pinch-zoom (ZoomableMediaBox), minus its double-tap and swipe-to-next handling,
                // neither of which apply to a single, non-paged canvas like this one.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var event: androidx.compose.ui.input.pointer.PointerEvent
                        do {
                            event = awaitPointerEvent()
                            val pointerCount = event.changes.size
                            if (pointerCount >= 2 || previewScale > 1f) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    val newScale = (previewScale * zoomChange).coerceIn(1f, 6f)
                                    previewScale = newScale
                                    previewOffset = if (newScale > 1f) {
                                        val maxOffsetX = previewBoxSize.width * (newScale - 1f) / 2f
                                        val maxOffsetY = previewBoxSize.height * (newScale - 1f) / 2f
                                        Offset(
                                            (previewOffset.x + panChange.x).coerceIn(-maxOffsetX, maxOffsetX),
                                            (previewOffset.y + panChange.y).coerceIn(-maxOffsetY, maxOffsetY),
                                        )
                                    } else {
                                        Offset.Zero
                                    }
                                    event.changes.forEach { change -> if (change.positionChanged()) change.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // Contain-fit within BOTH available dimensions, not just width -- a lopsided Free
            // ratio (e.g. 1:10, a tall narrow strip) sized off width alone would compute a height
            // many times taller than the actual screen, and since this Box doesn't clip, that
            // overflow just ran off both the top and bottom of the visible area with no way to
            // see or scroll to the rest of the canvas at all.
            val maxCanvasWidth = maxWidth * 0.94f
            val maxCanvasHeight = maxHeight * 0.94f
            val canvasWidthDp: androidx.compose.ui.unit.Dp
            val canvasHeightDp: androidx.compose.ui.unit.Dp
            if (maxCanvasWidth / effectiveCanvasRatio <= maxCanvasHeight) {
                canvasWidthDp = maxCanvasWidth
                canvasHeightDp = maxCanvasWidth / effectiveCanvasRatio
            } else {
                canvasHeightDp = maxCanvasHeight
                canvasWidthDp = maxCanvasHeight * effectiveCanvasRatio
            }
            BoxWithConstraints(
                Modifier
                    .size(canvasWidthDp, canvasHeightDp)
                    .graphicsLayer(
                        scaleX = previewScale,
                        scaleY = previewScale,
                        translationX = previewOffset.x,
                        translationY = previewOffset.y,
                    )
                    .background(Color(backgroundColorSeed))
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                        selectedItemIndex = null
                    },
            ) {
                val containerWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                val containerHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

                effectiveCells.forEach { cell ->
                    val photo = photos.getOrNull(cell.itemIndex) ?: return@forEach
                    val bitmap = bitmaps[photo.id]
                    val isSelected = selectedItemIndex == cell.itemIndex
                    val cellWidthDp = with(density) { (cell.wNorm * containerWidthPx).toDp() }
                    val cellHeightDp = with(density) { (cell.hNorm * containerHeightPx).toDp() }
                    Box(
                        Modifier
                            .offset {
                                IntOffset((cell.xNorm * containerWidthPx).roundToInt(), (cell.yNorm * containerHeightPx).roundToInt())
                            }
                            .size(cellWidthDp, cellHeightDp)
                            .padding(borderWidthDp.dp / 2)
                            .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
                            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                                if (!fitToContent) selectedItemIndex = cell.itemIndex
                            },
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = photo.displayName,
                                // Never crops -- the whole photo always stays visible, letterboxed
                                // within its cell if the cell's shape doesn't exactly match the
                                // photo's own aspect ratio (Fit mode sizes cells to avoid that;
                                // other modes may show a background-colored sliver on two sides).
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
                                CircularProgressIndicator(
                                    modifier = Modifier.align(Alignment.Center).size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollagePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.Black else Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** A small caps-style heading above a group of controls in the bottom panel -- gives each section
 * (layout, canvas shape, border, etc.) a visible name instead of leaving them to just run
 * together with nothing marking where one group ends and the next begins. */
@Composable
private fun CollageSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.85f),
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

@Composable
private fun CollageIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White)
    }
}

/** Decodes just [uri]'s pixel dimensions without loading its bytes -- used to compute Fit's
 * layout and the saved canvas's own resolution without holding every photo's full bitmap in
 * memory at once. */
private fun decodeBounds(context: Context, uri: Uri): Pair<Int, Int>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth to bounds.outHeight else null
}

private suspend fun loadDownsampledBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
    }

/** Above this, a decoded bitmap risks OutOfMemoryError once a few are combined into one collage
 * -- see [saveCollage]'s own doc comment for how this is used. */
private const val SAVE_MAX_DIMENSION = 3200

/**
 * Renders [cells] (or, if [fitLayout] is non-null, a layout computed fresh from each photo's own
 * aspect ratio -- see [fitRowLayout]/[fitColumnLayout]) onto a solid-[backgroundColorSeed] canvas,
 * then saves the result as a new photo.
 *
 * Every photo is always drawn in full, scaled but never cropped, centered within its own cell
 * (minus half [borderWidthDp] on every side, so the background shows through as a border/gap):
 * if a cell's shape doesn't exactly match its photo's aspect ratio, the extra space on two
 * opposite sides shows the background color instead of cutting anything off the photo. Fit mode
 * avoids this entirely by sizing cells to already match each photo's own aspect ratio.
 *
 * Every photo is independently re-decoded here at (up to) its own full native resolution --
 * reusing the editor's own downsampled preview bitmaps here (which this used to do) was the
 * actual cause of a saved collage looking noticeably softer than the photos it was built from:
 * the preview intentionally caps bitmaps at 1600px on the long side for on-screen drag
 * performance, but that cap has no business limiting the saved output's quality. [SAVE_MAX_DIMENSION]
 * is the one exception -- a ceiling purely to avoid OutOfMemoryError once several multi-megapixel
 * photos are decoded and composited together, well above what the old fixed 1600px preview-reuse
 * ever allowed.
 */
private suspend fun saveCollage(
    context: Context,
    photos: List<MediaItem>,
    cells: List<CollageCell>,
    fitLayout: CollagePreset?,
    canvasRatio: Float,
    borderWidthDp: Float,
    backgroundColorSeed: Long,
    folderPath: String,
) = withContext(Dispatchers.IO) {
    val bounds = photos.map { decodeBounds(context, it.uri) }
    val aspects = bounds.map { pair -> if (pair != null && pair.second > 0) pair.first.toFloat() / pair.second.toFloat() else 1f }

    val resolvedCells: List<CollageCell>
    val canvasW: Int
    val canvasH: Int
    if (fitLayout != null) {
        if (fitLayout == CollagePreset.COLUMN) {
            resolvedCells = fitColumnLayout(aspects)
            // Reference width = the tallest-relative-to-width photo's own native width, so no
            // photo in the column is ever scaled down below its native resolution; capped for
            // memory safety (see SAVE_MAX_DIMENSION).
            val referenceW = bounds.maxOfOrNull { it?.first ?: 0 }?.coerceAtLeast(1) ?: 1600
            canvasW = referenceW.coerceIn(1600, SAVE_MAX_DIMENSION)
            canvasH = (canvasW / fitColumnRatio(aspects)).roundToInt().coerceAtLeast(1)
        } else {
            resolvedCells = fitRowLayout(aspects)
            val referenceH = bounds.maxOfOrNull { it?.second ?: 0 }?.coerceAtLeast(1) ?: 1600
            canvasH = referenceH.coerceIn(1600, SAVE_MAX_DIMENSION)
            canvasW = (canvasH * fitRowRatio(aspects)).roundToInt().coerceAtLeast(1)
        }
    } else {
        resolvedCells = cells
        // Sized off the actual source photos' own resolution, not a fixed constant, so combining
        // two high-resolution photos doesn't throw away most of their detail -- capped only for
        // memory safety (see SAVE_MAX_DIMENSION).
        val referenceDim = bounds.maxOfOrNull { pair -> maxOf(pair?.first ?: 0, pair?.second ?: 0) }?.coerceAtLeast(1) ?: 1600
        val longSide = referenceDim.coerceIn(1600, SAVE_MAX_DIMENSION)
        if (canvasRatio >= 1f) {
            canvasW = longSide
            canvasH = (longSide / canvasRatio).roundToInt().coerceAtLeast(1)
        } else {
            canvasH = longSide
            canvasW = (longSide * canvasRatio).roundToInt().coerceAtLeast(1)
        }
    }

    val output = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    canvas.drawColor(backgroundColorSeed.toInt())

    val borderPx = borderWidthDp * canvasW / 360f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    for (cell in resolvedCells) {
        val photo = photos.getOrNull(cell.itemIndex) ?: continue
        val left = cell.xNorm * canvasW + borderPx / 2f
        val top = cell.yNorm * canvasH + borderPx / 2f
        val right = (cell.xNorm + cell.wNorm) * canvasW - borderPx / 2f
        val bottom = (cell.yNorm + cell.hNorm) * canvasH - borderPx / 2f
        if (right <= left || bottom <= top) continue

        val bitmap = loadDownsampledBitmap(context, photo.uri, SAVE_MAX_DIMENSION) ?: continue
        val destWidth = right - left
        val destHeight = bottom - top
        val destAspect = destWidth / destHeight
        val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        // Fit the whole photo inside the cell rect, never cropping it -- if the aspect ratios
        // don't exactly match, the leftover space on two opposite sides is left as background
        // (drawColor above already painted it) rather than cutting any of the photo away.
        val fittedRect = if (srcAspect > destAspect) {
            val fittedHeight = destWidth / srcAspect
            val yPad = (destHeight - fittedHeight) / 2f
            RectF(left, top + yPad, right, top + yPad + fittedHeight)
        } else {
            val fittedWidth = destHeight * srcAspect
            val xPad = (destWidth - fittedWidth) / 2f
            RectF(left + xPad, top, left + xPad + fittedWidth, bottom)
        }
        canvas.drawBitmap(bitmap, null, fittedRect, paint)
        bitmap.recycle()
    }

    val resolver = context.contentResolver
    val fileName = "collage_${System.currentTimeMillis() / 1000}.jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, if (folderPath.isEmpty()) "Pictures" else folderPath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri != null) {
        resolver.openOutputStream(uri)?.use { out -> output.compress(Bitmap.CompressFormat.JPEG, 95, out) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
    }
    output.recycle()
}
