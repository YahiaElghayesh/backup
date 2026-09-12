package com.elghayesh.gallerybackup.ui.edit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.outlined.Lens
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private enum class CropAspect(val label: String, val ratio: Float) {
    FREE("Free", 0f),
    SQUARE("1:1", 1f),
    FOUR_THREE("4:3", 4f / 3f),
    THREE_FOUR("3:4", 3f / 4f),
    SIXTEEN_NINE("16:9", 16f / 9f),
}

private enum class PhotoFilter(val label: String) {
    NONE("None"),
    VIVID("Vivid"),
    FADE("Fade"),
    NOIR("Noir"),
    VINTAGE("Vintage"),
    GRAYSCALE("Grayscale"),
    SEPIA("Sepia"),
    COOL("Cool"),
    WARM("Warm"),
    INVERT("Invert"),
}

private enum class EditTab(val label: String, val icon: ImageVector) {
    TRANSFORM("Transform", Icons.Filled.Crop),
    FILTER("Filter", Icons.Filled.FilterVintage),
    ADJUST("Adjust", Icons.Filled.Tune),
    FOCUS("Focus", Icons.Outlined.Lens),
    STICKER("Sticker", Icons.Filled.TextFields),
}

/** A crop rectangle normalized to 0..1 of the working bitmap's current width/height. */
private data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val FULL = NormRect(0f, 0f, 1f, 1f)
    }
}

private enum class CropCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

private data class TextSticker(
    val id: Long,
    val text: String,
    val xNorm: Float,
    val yNorm: Float,
    val colorArgb: Long,
)

/** A soft circular spotlight: everything outside [radiusNorm] of ([xNorm], [yNorm]) darkens by [strength]. */
private data class FocusSpot(
    val xNorm: Float = 0.5f,
    val yNorm: Float = 0.5f,
    val radiusNorm: Float = 0.35f,
    val strength: Float = 0f, // 0..100, 0 = off
)

/** Everything about the edit that undo/redo tracks as one snapshot. */
private data class EditState(
    val cropRect: NormRect = NormRect.FULL,
    val cropAspect: CropAspect = CropAspect.FREE,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val filter: PhotoFilter = PhotoFilter.NONE,
    val focus: FocusSpot = FocusSpot(),
    val stickers: List<TextSticker> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoEditScreen(
    item: MediaItem,
    viewModel: GalleryViewModel,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current

    var workingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var showSaveChoiceDialog by remember { mutableStateOf(false) }

    var tab by remember { mutableStateOf(EditTab.TRANSFORM) }
    var current by remember { mutableStateOf(EditState()) }
    val undoStack = remember { mutableStateListOf<EditState>() }
    val redoStack = remember { mutableStateListOf<EditState>() }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var editingStickerId by remember { mutableStateOf<Long?>(null) }
    var nextStickerId by remember { mutableStateOf(1L) }
    var showOriginal by remember { mutableStateOf(false) }
    var adjustParamIndex by remember { mutableStateOf(0) }
    var focusParamIndex by remember { mutableStateOf(0) }

    fun commit(newState: EditState) {
        undoStack.add(current)
        redoStack.clear()
        current = newState
    }

    // Sliders and drags mutate `current` live (for immediate preview) on every tick, so by the
    // time the gesture ends `current` already holds the final value -- commit(current) at that
    // point would push the state onto itself, breaking undo. These two instead capture the state
    // from *before* the gesture started once, on its first tick, and commit that captured
    // baseline only when the gesture ends.
    var dragBaseline by remember { mutableStateOf<EditState?>(null) }
    fun mutateLive(newState: EditState) {
        if (dragBaseline == null) dragBaseline = current
        current = newState
    }
    fun endLiveMutation() {
        val base = dragBaseline ?: return
        undoStack.add(base)
        redoStack.clear()
        dragBaseline = null
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(current)
        current = undoStack.removeAt(undoStack.lastIndex)
    }
    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(current)
        current = redoStack.removeAt(redoStack.lastIndex)
    }

    LaunchedEffect(item.uri) {
        isLoading = true
        workingBitmap = loadDownsampledBitmap(context, item.uri, maxDimension = 2048)
        isLoading = false
        current = EditState()
        undoStack.clear()
        redoStack.clear()
        dragBaseline = null
    }

    fun performSave(replace: Boolean) {
        val bitmap = workingBitmap ?: return
        val state = current
        isSaving = true
        scope.launch {
            saveEditedPhoto(context, bitmap, state, item, replace)
            if (replace) viewModel.deleteMediaItems(listOf(item), skipTrash = false)
            viewModel.refresh()
            isSaving = false
            onDone()
        }
    }

    if (showSaveChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showSaveChoiceDialog = false },
            title = { Text("Save changes") },
            text = { Text("Replace the original photo, or save your edit as a new file alongside it?") },
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

    editingStickerId?.let { id ->
        val sticker = current.stickers.find { it.id == id }
        StickerTextDialog(
            initialText = sticker?.text ?: "",
            onConfirm = { text ->
                val stickers = if (sticker != null) {
                    if (text.isBlank()) {
                        current.stickers.filter { it.id != id }
                    } else {
                        current.stickers.map { if (it.id == id) it.copy(text = text) else it }
                    }
                } else if (text.isNotBlank()) {
                    current.stickers + TextSticker(id, text, 0.5f, 0.5f, 0xFFFFFFFFL)
                } else {
                    current.stickers
                }
                commit(current.copy(stickers = stickers))
                editingStickerId = null
            },
            onDelete = if (sticker != null) {
                {
                    commit(current.copy(stickers = current.stickers.filter { it.id != id }))
                    editingStickerId = null
                }
            } else {
                null
            },
            onDismiss = { editingStickerId = null },
        )
    }

    val panelBg = Color(0xFF1C1C1C)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(enabled = undoStack.isNotEmpty(), onClick = { undo() }) {
                        Icon(
                            Icons.Filled.Undo,
                            contentDescription = "Undo",
                            tint = if (undoStack.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f),
                        )
                    }
                    IconButton(enabled = redoStack.isNotEmpty(), onClick = { redo() }) {
                        Icon(
                            Icons.Filled.Redo,
                            contentDescription = "Redo",
                            tint = if (redoStack.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.3f),
                        )
                    }
                    TextButton(
                        enabled = workingBitmap != null && !isSaving,
                        onClick = { showSaveChoiceDialog = true },
                    ) {
                        Text("Save", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
        bottomBar = {
            Column(Modifier.background(Color.Black)) {
                when (tab) {
                    EditTab.TRANSFORM -> Column(Modifier.background(panelBg).padding(vertical = 8.dp)) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                enabled = workingBitmap != null,
                                onClick = {
                                    val bitmap = workingBitmap ?: return@IconButton
                                    workingBitmap = rotateBitmap90(bitmap)
                                    // A crop/sticker layout from before the rotation no longer lines up
                                    // with the new orientation, so this starts a fresh layout on it.
                                    commit(EditState())
                                },
                            ) {
                                Icon(Icons.Filled.RotateRight, contentDescription = "Rotate", tint = Color.White)
                            }
                            CropAspect.entries.forEach { a ->
                                DarkPill(
                                    label = a.label,
                                    selected = current.cropAspect == a,
                                    onClick = {
                                        val bitmap = workingBitmap
                                        val rect = if (a == CropAspect.FREE || bitmap == null) {
                                            NormRect.FULL
                                        } else {
                                            applyAspectLock(current.cropRect, a.ratio, bitmap.width, bitmap.height)
                                        }
                                        commit(current.copy(cropAspect = a, cropRect = rect))
                                    },
                                )
                            }
                        }
                    }
                    EditTab.FILTER -> Row(
                        Modifier.background(panelBg).fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PhotoFilter.entries.forEach { f ->
                            DarkPill(label = f.label, selected = current.filter == f, onClick = { commit(current.copy(filter = f)) })
                        }
                    }
                    EditTab.ADJUST -> GestureAdjustPanel(
                        background = panelBg,
                        labels = listOf("Brightness", "Contrast", "Saturation", "Warmth", "Highlights", "Shadows"),
                        values = listOf(
                            current.brightness, current.contrast, current.saturation,
                            current.warmth, current.highlights, current.shadows,
                        ),
                        ranges = List(6) { -100f..100f },
                        selectedIndex = adjustParamIndex,
                        onSelect = { adjustParamIndex = it },
                        onChange = { index, value ->
                            mutateLive(
                                when (index) {
                                    0 -> current.copy(brightness = value)
                                    1 -> current.copy(contrast = value)
                                    2 -> current.copy(saturation = value)
                                    3 -> current.copy(warmth = value)
                                    4 -> current.copy(highlights = value)
                                    else -> current.copy(shadows = value)
                                },
                            )
                        },
                        onChangeFinished = { endLiveMutation() },
                    )
                    EditTab.FOCUS -> Column {
                        Text(
                            "Drag the circle on the photo to move the spotlight once strength is above zero.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.background(panelBg).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        GestureAdjustPanel(
                            background = panelBg,
                            labels = listOf("Strength", "Spotlight size"),
                            values = listOf(current.focus.strength, current.focus.radiusNorm * 100f),
                            ranges = listOf(0f..100f, 10f..80f),
                            selectedIndex = focusParamIndex,
                            onSelect = { focusParamIndex = it },
                            onChange = { index, value ->
                                mutateLive(
                                    if (index == 0) {
                                        current.copy(focus = current.focus.copy(strength = value.coerceIn(0f, 100f)))
                                    } else {
                                        current.copy(focus = current.focus.copy(radiusNorm = (value / 100f).coerceIn(0.1f, 0.8f)))
                                    },
                                )
                            },
                            onChangeFinished = { endLiveMutation() },
                        )
                    }
                    EditTab.STICKER -> Column(Modifier.background(panelBg).fillMaxWidth().padding(16.dp)) {
                        TextButton(onClick = { editingStickerId = nextStickerId; nextStickerId += 1 }) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text("Add text", color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            "Drag a sticker to move it, or tap it to edit or remove it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    EditTab.entries.forEach { t ->
                        ToolDockButton(tab = t, selected = tab == t, onClick = { tab = t })
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val bitmap = workingBitmap
            when {
                isLoading -> CircularProgressIndicator(color = Color.White)
                bitmap == null -> Text("Couldn't load this photo.", color = Color.White)
                else -> {
                    val composeMatrix = buildColorMatrix(current)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                            .onSizeChanged { boxSize = it }
                            // Press and hold anywhere on the photo to instantly preview the untouched
                            // original -- release to go back to the edited version.
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        showOriginal = true
                                        tryAwaitRelease()
                                        showOriginal = false
                                    },
                                )
                            },
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = item.displayName,
                            contentScale = ContentScale.Fit,
                            colorFilter = if (showOriginal) {
                                null
                            } else {
                                ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(composeMatrix.array))
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (!showOriginal) {
                            if (current.focus.strength > 0f) {
                                FocusOverlay(current.focus)
                            }
                            current.stickers.forEach { sticker ->
                                Text(
                                    sticker.text,
                                    color = Color(sticker.colorArgb),
                                    fontSize = 22.sp,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .normOffset(sticker.xNorm, sticker.yNorm, boxSize, density)
                                        .pointerInput(sticker.id, boxSize) {
                                            detectDragImmediate(
                                                onDragEnd = { endLiveMutation() },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    if (boxSize.width > 0 && boxSize.height > 0) {
                                                        mutateLive(
                                                            current.copy(
                                                                stickers = current.stickers.map {
                                                                    if (it.id == sticker.id) {
                                                                        it.copy(
                                                                            xNorm = (it.xNorm + dragAmount.x / boxSize.width).coerceIn(0f, 1f),
                                                                            yNorm = (it.yNorm + dragAmount.y / boxSize.height).coerceIn(0f, 1f),
                                                                        )
                                                                    } else {
                                                                        it
                                                                    }
                                                                },
                                                            ),
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                        .clickable { editingStickerId = sticker.id }
                                        .background(Color.Black.copy(alpha = 0.25f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            if (tab == EditTab.TRANSFORM) {
                                CropOverlay(
                                    rect = current.cropRect,
                                    boxSize = boxSize,
                                    density = density,
                                    onCornerDrag = { corner, dxNorm, dyNorm ->
                                        var rect = updatedCropRect(current.cropRect, corner, dxNorm, dyNorm)
                                        if (current.cropAspect != CropAspect.FREE && bitmap.width > 0 && bitmap.height > 0) {
                                            rect = applyAspectLock(rect, current.cropAspect.ratio, bitmap.width, bitmap.height)
                                        }
                                        mutateLive(current.copy(cropRect = rect))
                                    },
                                    onDragEnd = { endLiveMutation() },
                                )
                            }
                            if (tab == EditTab.FOCUS && current.focus.strength > 0f) {
                                FocusHandle(
                                    focus = current.focus,
                                    boxSize = boxSize,
                                    density = density,
                                    onMove = { xNorm, yNorm ->
                                        mutateLive(current.copy(focus = current.focus.copy(xNorm = xNorm, yNorm = yNorm)))
                                    },
                                    onDragEnd = { endLiveMutation() },
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
private fun ToolDockButton(tab: EditTab, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Color.White else Color.White.copy(alpha = 0.5f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(tab.label, color = tint, style = MaterialTheme.typography.labelSmall)
        if (selected) {
            Spacer(Modifier.height(2.dp))
            Box(Modifier.size(width = 16.dp, height = 2.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
private fun DarkPill(label: String, selected: Boolean, onClick: () -> Unit) {
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

/**
 * The Snapseed-style fine-tune control: no visible slider bar over the photo. Swipe vertically to
 * pick which parameter you're adjusting (shown as a row of dots), swipe horizontally to change its
 * value, read the live number in the middle. The gesture commits to whichever direction the first
 * few pixels of movement suggest and stays in that mode for the rest of the drag, so a slightly
 * diagonal swipe doesn't flicker between switching parameters and changing a value.
 */
@Composable
private fun GestureAdjustPanel(
    background: Color,
    labels: List<String>,
    values: List<Float>,
    ranges: List<ClosedFloatingPointRange<Float>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onChange: (index: Int, value: Float) -> Unit,
    onChangeFinished: () -> Unit,
) {
    var panelWidthPx by remember { mutableStateOf(1f) }
    val label = labels.getOrElse(selectedIndex) { "" }
    val value = values.getOrElse(selectedIndex) { 0f }
    // pointerInput below is keyed only on (selectedIndex, labels.size), not on `values` itself --
    // restarting it on every value tick would abort an in-progress drag. That means its coroutine
    // can outlive several recompositions, so `values` must be read through rememberUpdatedState:
    // otherwise a second drag on the same parameter (no index switch in between) would compute its
    // starting point from whatever `values` was when the coroutine last (re)launched, not the value
    // the first drag actually left it at.
    val latestValues = rememberUpdatedState(values)

    Column(
        Modifier
            .fillMaxWidth()
            .background(background)
            .onSizeChanged { panelWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(selectedIndex, labels.size) {
                var mode = 0 // 0 = undecided, 1 = switching parameter, 2 = changing its value
                var accDx = 0f
                var accDy = 0f
                var startValue = 0f
                detectDragGestures(
                    onDragStart = {
                        mode = 0
                        accDx = 0f
                        accDy = 0f
                        startValue = latestValues.value.getOrElse(selectedIndex) { 0f }
                    },
                    onDragEnd = {
                        if (mode == 1) {
                            if (accDy < -32f && selectedIndex > 0) onSelect(selectedIndex - 1)
                            else if (accDy > 32f && selectedIndex < labels.lastIndex) onSelect(selectedIndex + 1)
                        } else if (mode == 2) {
                            onChangeFinished()
                        }
                    },
                    onDragCancel = { if (mode == 2) onChangeFinished() },
                ) { change, dragAmount ->
                    change.consume()
                    accDx += dragAmount.x
                    accDy += dragAmount.y
                    if (mode == 0 && (abs(accDx) > 12f || abs(accDy) > 12f)) {
                        mode = if (abs(accDx) > abs(accDy)) 2 else 1
                    }
                    if (mode == 2) {
                        val range = ranges.getOrElse(selectedIndex) { -100f..100f }
                        val span = range.endInclusive - range.start
                        val delta = accDx / panelWidthPx * span
                        onChange(selectedIndex, (startValue + delta).coerceIn(range.start, range.endInclusive))
                    }
                }
            }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
        Text(
            value.roundToInt().toString(),
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.indices.forEach { i ->
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (i == selectedIndex) Color.White else Color.White.copy(alpha = 0.3f)),
                )
            }
        }
    }
}

/**
 * Positions this element at ([xNorm], [yNorm]) fractions of [boxSize], measured in pixels via
 * [offset] (not [padding], which asserts non-negative values and would crash once a centered
 * handle's top-left goes negative near an edge). [centerOnPointDp] shifts the element so it's
 * centered ON that point rather than anchored there by its own top-left corner -- pass the
 * element's own size for a drag handle that should sit right on the point it represents; leave
 * it 0.dp (the default) for something like sticker text that's meant to start at the point.
 */
private fun Modifier.normOffset(
    xNorm: Float,
    yNorm: Float,
    boxSize: IntSize,
    density: androidx.compose.ui.unit.Density,
    centerOnPointDp: androidx.compose.ui.unit.Dp = 0.dp,
): Modifier = this.offset {
    with(density) {
        val half = centerOnPointDp.toPx() / 2f
        IntOffset(
            (xNorm * boxSize.width - half).roundToInt(),
            (yNorm * boxSize.height - half).roundToInt(),
        )
    }
}

/**
 * Like [detectDragGestures], but consumes the initial pointer-down immediately instead of only
 * once a drag actually starts. Drag handles (crop corners, focus spot, stickers) sit on top of
 * this screen's whole-image "press and hold to preview original" gesture; that outer gesture
 * only fires on an *unconsumed* down (see [detectTapGestures]'s use of `awaitFirstDown()`), which
 * is otherwise still unconsumed while a handle is merely waiting to see if this touch turns into
 * a drag. Without this, touching a handle also flips on the original-photo preview, which yanks
 * the handle out of composition mid-touch and aborts the drag -- every time, not just once.
 */
private suspend fun PointerInputScope.detectDragImmediate(
    onDragEnd: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val pointerId = down.id
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            if (change.changedToUpIgnoreConsumed()) {
                change.consume()
                onDragEnd()
                break
            }
            val dragAmount = change.positionChange()
            change.consume()
            onDrag(change, dragAmount)
        }
    }
}

@Composable
private fun CropOverlay(
    rect: NormRect,
    boxSize: IntSize,
    density: androidx.compose.ui.unit.Density,
    onCornerDrag: (CropCorner, Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val left = rect.left * size.width
        val top = rect.top * size.height
        val right = rect.right * size.width
        val bottom = rect.bottom * size.height
        val scrim = Color.Black.copy(alpha = 0.55f)
        drawRect(color = scrim, topLeft = Offset(0f, 0f), size = Size(size.width, top))
        drawRect(color = scrim, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
        drawRect(color = scrim, topLeft = Offset(0f, top), size = Size(left, bottom - top))
        drawRect(color = scrim, topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))
        drawRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = 2.dp.toPx()),
        )
        // Rule-of-thirds grid inside the crop rect.
        val gridColor = Color.White.copy(alpha = 0.6f)
        for (i in 1..2) {
            val x = left + (right - left) * i / 3f
            drawLine(gridColor, Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx())
            val y = top + (bottom - top) * i / 3f
            drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
        }
        // Corner brackets, drawn INWARD from each corner along the crop rect's own edges --
        // unlike the draggable circle handles below (centered exactly ON the corner point), these
        // never extend past the rect's own bounds. That matters because the rect starts out
        // covering the whole image (in Free mode, before the user has dragged anything), which
        // puts every corner exactly on the image's own edge -- a handle centered there is half
        // clipped by the screen/layout edge and barely visible. These brackets stay fully visible
        // and clearly mark all four corners regardless.
        val bracketLen = 18.dp.toPx()
        val bracketStroke = 4.dp.toPx()
        fun DrawScope.drawCornerBracket(cornerX: Float, cornerY: Float, dirX: Float, dirY: Float) {
            drawLine(Color.White, Offset(cornerX, cornerY), Offset(cornerX + dirX * bracketLen, cornerY), strokeWidth = bracketStroke)
            drawLine(Color.White, Offset(cornerX, cornerY), Offset(cornerX, cornerY + dirY * bracketLen), strokeWidth = bracketStroke)
        }
        drawCornerBracket(left, top, 1f, 1f)
        drawCornerBracket(right, top, -1f, 1f)
        drawCornerBracket(left, bottom, 1f, -1f)
        drawCornerBracket(right, bottom, -1f, -1f)
    }
    Box(
        Modifier
            .normOffset(rect.left, rect.top, boxSize, density, centerOnPointDp = 40.dp)
            .size(40.dp)
            .pointerInput(boxSize) {
                detectDragImmediate(onDragEnd = { onDragEnd() }) { change, dragAmount ->
                    change.consume()
                    if (boxSize.width > 0 && boxSize.height > 0) {
                        onCornerDrag(CropCorner.TOP_LEFT, dragAmount.x / boxSize.width, dragAmount.y / boxSize.height)
                    }
                }
            }
            .background(Color.White, CircleShape)
            .border(2.dp, Color.Black, CircleShape),
    )
    Box(
        Modifier
            .normOffset(rect.right, rect.top, boxSize, density, centerOnPointDp = 40.dp)
            .size(40.dp)
            .pointerInput(boxSize) {
                detectDragImmediate(onDragEnd = { onDragEnd() }) { change, dragAmount ->
                    change.consume()
                    if (boxSize.width > 0 && boxSize.height > 0) {
                        onCornerDrag(CropCorner.TOP_RIGHT, dragAmount.x / boxSize.width, dragAmount.y / boxSize.height)
                    }
                }
            }
            .background(Color.White, CircleShape)
            .border(2.dp, Color.Black, CircleShape),
    )
    Box(
        Modifier
            .normOffset(rect.left, rect.bottom, boxSize, density, centerOnPointDp = 40.dp)
            .size(40.dp)
            .pointerInput(boxSize) {
                detectDragImmediate(onDragEnd = { onDragEnd() }) { change, dragAmount ->
                    change.consume()
                    if (boxSize.width > 0 && boxSize.height > 0) {
                        onCornerDrag(CropCorner.BOTTOM_LEFT, dragAmount.x / boxSize.width, dragAmount.y / boxSize.height)
                    }
                }
            }
            .background(Color.White, CircleShape)
            .border(2.dp, Color.Black, CircleShape),
    )
    Box(
        Modifier
            .normOffset(rect.right, rect.bottom, boxSize, density, centerOnPointDp = 40.dp)
            .size(40.dp)
            .pointerInput(boxSize) {
                detectDragImmediate(onDragEnd = { onDragEnd() }) { change, dragAmount ->
                    change.consume()
                    if (boxSize.width > 0 && boxSize.height > 0) {
                        onCornerDrag(CropCorner.BOTTOM_RIGHT, dragAmount.x / boxSize.width, dragAmount.y / boxSize.height)
                    }
                }
            }
            .background(Color.White, CircleShape)
            .border(2.dp, Color.Black, CircleShape),
    )
}

private fun updatedCropRect(rect: NormRect, corner: CropCorner, dxNorm: Float, dyNorm: Float): NormRect {
    val minSize = 0.08f
    return when (corner) {
        CropCorner.TOP_LEFT -> rect.copy(
            left = (rect.left + dxNorm).coerceIn(0f, rect.right - minSize),
            top = (rect.top + dyNorm).coerceIn(0f, rect.bottom - minSize),
        )
        CropCorner.TOP_RIGHT -> rect.copy(
            right = (rect.right + dxNorm).coerceIn(rect.left + minSize, 1f),
            top = (rect.top + dyNorm).coerceIn(0f, rect.bottom - minSize),
        )
        CropCorner.BOTTOM_LEFT -> rect.copy(
            left = (rect.left + dxNorm).coerceIn(0f, rect.right - minSize),
            bottom = (rect.bottom + dyNorm).coerceIn(rect.top + minSize, 1f),
        )
        CropCorner.BOTTOM_RIGHT -> rect.copy(
            right = (rect.right + dxNorm).coerceIn(rect.left + minSize, 1f),
            bottom = (rect.bottom + dyNorm).coerceIn(rect.top + minSize, 1f),
        )
    }
}

/** Re-derives an aspect-locked rect centered on [rect]'s current center, sized to fit within it. */
private fun applyAspectLock(rect: NormRect, ratio: Float, bitmapW: Int, bitmapH: Int): NormRect {
    val cx = (rect.left + rect.right) / 2f
    val cy = (rect.top + rect.bottom) / 2f
    val wPx = (rect.right - rect.left) * bitmapW
    val hPx = (rect.bottom - rect.top) * bitmapH
    var newWPx = wPx
    var newHPx = wPx / ratio
    if (newHPx > hPx) {
        newHPx = hPx
        newWPx = hPx * ratio
    }
    val newWNorm = (newWPx / bitmapW).coerceIn(0.1f, 1f)
    val newHNorm = (newHPx / bitmapH).coerceIn(0.1f, 1f)
    var left = cx - newWNorm / 2f
    var right = cx + newWNorm / 2f
    var top = cy - newHNorm / 2f
    var bottom = cy + newHNorm / 2f
    if (left < 0f) { right -= left; left = 0f }
    if (right > 1f) { left -= (right - 1f); right = 1f }
    if (top < 0f) { bottom -= top; top = 0f }
    if (bottom > 1f) { top -= (bottom - 1f); bottom = 1f }
    return NormRect(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))
}

@Composable
private fun FocusOverlay(focus: FocusSpot) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = focus.xNorm * size.width
        val cy = focus.yNorm * size.height
        val radius = focus.radiusNorm * min(size.width, size.height)
        val alpha = (focus.strength / 100f).coerceIn(0f, 1f) * 0.7f
        val brush = androidx.compose.ui.graphics.Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.6f to Color.Transparent,
                1f to Color.Black.copy(alpha = alpha),
            ),
            center = Offset(cx, cy),
            radius = radius * 2.2f,
        )
        drawRect(brush)
    }
}

@Composable
private fun FocusHandle(
    focus: FocusSpot,
    boxSize: IntSize,
    density: androidx.compose.ui.unit.Density,
    onMove: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Box(
        Modifier
            .normOffset(focus.xNorm, focus.yNorm, boxSize, density, centerOnPointDp = 32.dp)
            .size(32.dp)
            .pointerInput(boxSize) {
                detectDragImmediate(onDragEnd = { onDragEnd() }) { change, dragAmount ->
                    change.consume()
                    if (boxSize.width > 0 && boxSize.height > 0) {
                        onMove(
                            (focus.xNorm + dragAmount.x / boxSize.width).coerceIn(0f, 1f),
                            (focus.yNorm + dragAmount.y / boxSize.height).coerceIn(0f, 1f),
                        )
                    }
                }
            }
            .border(2.dp, Color.White, CircleShape),
    )
}

@Composable
private fun StickerTextDialog(
    initialText: String,
    onConfirm: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text sticker") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text") })
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("Done") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun buildColorMatrix(state: EditState): android.graphics.ColorMatrix {
    val result = android.graphics.ColorMatrix()
    result.postConcat(android.graphics.ColorMatrix().apply { setSaturation(1f + state.saturation / 100f) })
    val contrastScale = 1f + state.contrast / 100f
    val brightnessOffset = state.brightness / 100f * 255f
    // Highlights/shadows are approximated as gentle overall brightness nudges rather than a true
    // tone curve (which needs per-pixel processing, not a single linear matrix) -- reasonable for a
    // quick preview, but not a substitute for a real levels/curves tool.
    val toneOffset = (state.highlights + state.shadows) / 100f * 40f
    val warmR = state.warmth / 100f * 25f
    val warmB = -state.warmth / 100f * 25f
    result.postConcat(
        android.graphics.ColorMatrix(
            floatArrayOf(
                contrastScale, 0f, 0f, 0f, brightnessOffset + toneOffset + warmR,
                0f, contrastScale, 0f, 0f, brightnessOffset + toneOffset,
                0f, 0f, contrastScale, 0f, brightnessOffset + toneOffset + warmB,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    filterMatrixOrNull(state.filter)?.let { result.postConcat(it) }
    return result
}

private fun filterMatrixOrNull(filter: PhotoFilter): android.graphics.ColorMatrix? = when (filter) {
    PhotoFilter.NONE -> null
    PhotoFilter.GRAYSCALE -> android.graphics.ColorMatrix().apply { setSaturation(0f) }
    PhotoFilter.SEPIA -> android.graphics.ColorMatrix(
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    PhotoFilter.COOL -> android.graphics.ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, -10f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 20f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    PhotoFilter.WARM -> android.graphics.ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 20f,
            0f, 1f, 0f, 0f, 10f,
            0f, 0f, 1f, 0f, -10f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    PhotoFilter.INVERT -> android.graphics.ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    PhotoFilter.VIVID -> android.graphics.ColorMatrix().apply { setSaturation(1.5f) }
    PhotoFilter.FADE -> android.graphics.ColorMatrix(
        floatArrayOf(
            0.9f, 0f, 0f, 0f, 25f,
            0f, 0.9f, 0f, 0f, 25f,
            0f, 0f, 0.9f, 0f, 25f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    PhotoFilter.NOIR -> android.graphics.ColorMatrix().apply {
        setSaturation(0f)
        postConcat(
            android.graphics.ColorMatrix(
                floatArrayOf(
                    1.3f, 0f, 0f, 0f, -25f,
                    0f, 1.3f, 0f, 0f, -25f,
                    0f, 0f, 1.3f, 0f, -25f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    PhotoFilter.VINTAGE -> android.graphics.ColorMatrix(
        floatArrayOf(
            0.6f, 0.3f, 0.1f, 0f, 15f,
            0.2f, 0.65f, 0.15f, 0f, 10f,
            0.15f, 0.25f, 0.5f, 0f, 5f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

private fun rotateBitmap90(bitmap: Bitmap): Bitmap {
    val matrix = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
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

private fun cropRectFor(bitmapW: Int, bitmapH: Int, rect: NormRect): Rect {
    val left = (rect.left * bitmapW).roundToInt().coerceIn(0, bitmapW - 1)
    val top = (rect.top * bitmapH).roundToInt().coerceIn(0, bitmapH - 1)
    val right = (rect.right * bitmapW).roundToInt().coerceIn(left + 1, bitmapW)
    val bottom = (rect.bottom * bitmapH).roundToInt().coerceIn(top + 1, bitmapH)
    return Rect(left, top, right, bottom)
}

private suspend fun saveEditedPhoto(
    context: Context,
    bitmap: Bitmap,
    state: EditState,
    original: MediaItem,
    replace: Boolean,
) = withContext(Dispatchers.IO) {
    val cropRect = cropRectFor(bitmap.width, bitmap.height, state.cropRect)
    val cropped = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())

    val output = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    val paint = Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(buildColorMatrix(state)) }
    canvas.drawBitmap(cropped, 0f, 0f, paint)

    if (state.focus.strength > 0f) {
        val cx = (state.focus.xNorm - state.cropRect.left) / (state.cropRect.right - state.cropRect.left) * output.width
        val cy = (state.focus.yNorm - state.cropRect.top) / (state.cropRect.bottom - state.cropRect.top) * output.height
        val radius = state.focus.radiusNorm * min(output.width, output.height)
        val alpha = ((state.focus.strength / 100f).coerceIn(0f, 1f) * 0.7f * 255).toInt()
        val shader = android.graphics.RadialGradient(
            cx, cy, radius * 2.2f,
            intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(alpha, 0, 0, 0)),
            floatArrayOf(0f, 0.6f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, output.width.toFloat(), output.height.toFloat(), Paint().apply { this.shader = shader })
    }

    if (state.stickers.isNotEmpty()) {
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = output.width / 18f
        }
        for (sticker in state.stickers) {
            val relX = (sticker.xNorm - state.cropRect.left) / (state.cropRect.right - state.cropRect.left)
            val relY = (sticker.yNorm - state.cropRect.top) / (state.cropRect.bottom - state.cropRect.top)
            textPaint.color = sticker.colorArgb.toInt()
            canvas.drawText(sticker.text, relX * output.width, relY * output.height, textPaint)
        }
    }

    val resolver = context.contentResolver
    val baseName = original.displayName.substringBeforeLast('.', original.displayName)
    val fileName = if (replace) "$baseName.jpg" else "${baseName}_edited_${System.currentTimeMillis() / 1000}.jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                if (original.folderPath.isEmpty()) "Pictures" else original.folderPath,
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri != null) {
        resolver.openOutputStream(uri)?.use { out -> output.compress(Bitmap.CompressFormat.JPEG, 92, out) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val doneValues = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, doneValues, null, null)
        }
    }
}
