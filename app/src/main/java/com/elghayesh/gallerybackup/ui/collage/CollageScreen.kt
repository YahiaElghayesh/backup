package com.elghayesh.gallerybackup.ui.collage

import androidx.activity.compose.BackHandler
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
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
 * (can go slightly negative or past 1 while being dragged/resized -- clamped generously rather
 * than tightly, so a photo can be positioned right up to, or slightly past, the canvas edge). */
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
    FREE("Free", null),
}

/**
 * Builds a collage from 2+ selected photos: pick a starting layout preset (or leave the default),
 * then freely drag to reposition and drag the corner handle to resize any photo -- the presets are
 * just a starting point, not a constraint. Canvas size is a preset aspect ratio or fully free
 * (independent width/height sliders). Border width and background color are shared across every
 * gap between/around photos.
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

    var cells by remember { mutableStateOf(CollagePreset.GRID.build(photos.size)) }
    var selectedItemIndex by remember { mutableStateOf<Int?>(null) }
    var canvasAspectPreset by remember { mutableStateOf(CanvasAspectPreset.SQUARE) }
    var freeWidthText by remember { mutableStateOf("4") }
    var freeHeightText by remember { mutableStateOf("3") }
    var borderWidthDp by remember { mutableStateOf(4f) }
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
            loadDownsampledBitmap(context, photo.uri, maxDimension = 1600)?.let { bitmaps[photo.id] = it }
        }
    }

    // No upper limit on what can be typed here -- any positive number is a valid ratio.
    val freeWidthValue = freeWidthText.toFloatOrNull()?.takeIf { it > 0f } ?: 1f
    val freeHeightValue = freeHeightText.toFloatOrNull()?.takeIf { it > 0f } ?: 1f
    val canvasRatio = canvasAspectPreset.ratio ?: (freeWidthValue / freeHeightValue)

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
        val snapshotRatio = canvasRatio
        val snapshotBorder = borderWidthDp
        val snapshotBackground = backgroundColorSeed
        // Same folder as the source photos rather than a fixed "Collages" folder -- the first
        // selected photo's own folder, since a collage combining photos from different folders
        // has no single obviously-correct destination.
        val folderPath = photos.first().folderPath
        scope.launch {
            saveCollage(context, photos, snapshotCells, snapshotRatio, snapshotBorder, snapshotBackground, folderPath, bitmaps)
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
            Column(Modifier.background(panelBg).padding(vertical = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CollagePreset.entries.forEach { preset ->
                        CollagePill(
                            label = preset.label,
                            selected = false,
                            onClick = { cells = preset.build(photos.size); selectedItemIndex = null },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CanvasAspectPreset.entries.forEach { a ->
                        CollagePill(label = a.label, selected = canvasAspectPreset == a, onClick = { canvasAspectPreset = a })
                    }
                }
                if (canvasAspectPreset == CanvasAspectPreset.FREE) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Canvas shape -- width : height",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                Spacer(Modifier.height(8.dp))
                Text(
                    "Border -- ${borderWidthDp.roundToInt()}dp",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Slider(
                    value = borderWidthDp,
                    onValueChange = { borderWidthDp = it },
                    valueRange = 0f..150f,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
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
                if (selectedItemIndex != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Selected photo",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val step = 0.04f
                        val selIndex = selectedItemIndex!!
                        CollageIconButton(Icons.Filled.Remove, "Smaller") { resizeCell(selIndex, -step, -step) }
                        CollageIconButton(Icons.Filled.Add, "Bigger") { resizeCell(selIndex, step, step) }
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
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            // Contain-fit within BOTH available dimensions, not just width -- a lopsided Free
            // ratio (e.g. 1:10, a tall narrow strip) sized off width alone would compute a height
            // many times taller than the actual screen, and since this Box doesn't clip, that
            // overflow just ran off both the top and bottom of the visible area with no way to
            // see or scroll to the rest of the canvas at all.
            val maxCanvasWidth = maxWidth * 0.94f
            val maxCanvasHeight = maxHeight * 0.94f
            val canvasWidthDp: androidx.compose.ui.unit.Dp
            val canvasHeightDp: androidx.compose.ui.unit.Dp
            if (maxCanvasWidth / canvasRatio <= maxCanvasHeight) {
                canvasWidthDp = maxCanvasWidth
                canvasHeightDp = maxCanvasWidth / canvasRatio
            } else {
                canvasHeightDp = maxCanvasHeight
                canvasWidthDp = maxCanvasHeight * canvasRatio
            }
            BoxWithConstraints(
                Modifier
                    .size(canvasWidthDp, canvasHeightDp)
                    .background(Color(backgroundColorSeed))
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {
                        selectedItemIndex = null
                    },
            ) {
                val containerWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                val containerHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

                cells.forEach { cell ->
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
                            .pointerInputMove(
                                onStart = { selectedItemIndex = cell.itemIndex },
                                onDrag = { dx, dy -> moveCell(cell.itemIndex, dx / containerWidthPx, dy / containerHeightPx) },
                            ),
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = photo.displayName,
                                contentScale = ContentScale.Crop,
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

/**
 * Drag anywhere in this element -- [onStart] fires on the very first touch-down (not only once a
 * drag has actually started past some movement threshold, which is how [detectDragGestures]'s own
 * onDragStart behaves -- that would mean a plain tap with no movement never selects anything).
 * The down event is also consumed immediately, so a tap that starts on a cell can never fall
 * through to an ancestor's own tap handler (here, the canvas's tap-to-deselect) -- the same
 * technique PhotoEditScreen's crop/focus/sticker handles already use, for the same reason.
 */
private fun Modifier.pointerInputMove(onStart: () -> Unit, onDrag: (dx: Float, dy: Float) -> Unit): Modifier =
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            onStart()
            val pointerId = down.id
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (!change.pressed) {
                    change.consume()
                    break
                }
                val dragAmount = change.positionChange()
                change.consume()
                if (dragAmount != Offset.Zero) {
                    onDrag(dragAmount.x, dragAmount.y)
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

@Composable
private fun CollageIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White)
    }
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

/**
 * Renders [cells] onto a solid-[backgroundColorSeed] canvas at [canvasRatio], each photo
 * center-cropped to exactly fill its own cell rect (minus half [borderWidthDp] on every side, so
 * the background shows through as a border/gap between and around photos), then saves the result
 * as a new photo. [borderWidthDp] is scaled against an assumed ~360dp-wide on-screen preview so
 * the saved border reads proportionally the same as what was being edited, since the output
 * bitmap's own pixel size has no direct relationship to screen density.
 */
private suspend fun saveCollage(
    context: Context,
    photos: List<MediaItem>,
    cells: List<CollageCell>,
    canvasRatio: Float,
    borderWidthDp: Float,
    backgroundColorSeed: Long,
    folderPath: String,
    bitmaps: Map<Long, Bitmap>,
) = withContext(Dispatchers.IO) {
    val longSide = 1600
    val canvasW: Int
    val canvasH: Int
    if (canvasRatio >= 1f) {
        canvasW = longSide
        canvasH = (longSide / canvasRatio).roundToInt().coerceAtLeast(1)
    } else {
        canvasH = longSide
        canvasW = (longSide * canvasRatio).roundToInt().coerceAtLeast(1)
    }

    val output = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    canvas.drawColor(backgroundColorSeed.toInt())

    val borderPx = borderWidthDp * canvasW / 360f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    for (cell in cells) {
        val photo = photos.getOrNull(cell.itemIndex) ?: continue
        val bitmap = bitmaps[photo.id] ?: continue
        val left = cell.xNorm * canvasW + borderPx / 2f
        val top = cell.yNorm * canvasH + borderPx / 2f
        val right = (cell.xNorm + cell.wNorm) * canvasW - borderPx / 2f
        val bottom = (cell.yNorm + cell.hNorm) * canvasH - borderPx / 2f
        if (right <= left || bottom <= top) continue

        val destAspect = (right - left) / (bottom - top)
        val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val srcRect = if (srcAspect > destAspect) {
            val cropWidth = (bitmap.height * destAspect).roundToInt().coerceIn(1, bitmap.width)
            val xOffset = (bitmap.width - cropWidth) / 2
            Rect(xOffset, 0, xOffset + cropWidth, bitmap.height)
        } else {
            val cropHeight = (bitmap.width / destAspect).roundToInt().coerceIn(1, bitmap.height)
            val yOffset = (bitmap.height - cropHeight) / 2
            Rect(0, yOffset, bitmap.width, yOffset + cropHeight)
        }
        canvas.drawBitmap(bitmap, srcRect, RectF(left, top, right, bottom), paint)
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
        resolver.openOutputStream(uri)?.use { out -> output.compress(Bitmap.CompressFormat.JPEG, 92, out) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
    }
}
