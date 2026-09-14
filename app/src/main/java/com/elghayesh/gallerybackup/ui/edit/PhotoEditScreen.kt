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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PathOperation
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elghayesh.gallerybackup.data.media.MediaItem
import com.elghayesh.gallerybackup.data.settings.AccentColor
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private enum class CropAspect(val label: String, val ratio: Float) {
    FREE("Free", 0f),
    SQUARE("1:1", 1f),
    FOUR_THREE("4:3", 4f / 3f),
    THREE_FOUR("3:4", 3f / 4f),
    SIXTEEN_NINE("16:9", 16f / 9f),
}

private enum class EditTab(val label: String, val icon: ImageVector) {
    CROP("Crop", Icons.Filled.Crop),
    FREE_CORNERS("Free corners", Icons.Filled.CropFree),
    PERSPECTIVE("Perspective", Icons.Filled.CropRotate),
    ADJUST("Adjust", Icons.Filled.Tune),
    STICKER("Sticker", Icons.Filled.TextFields),
}

/** A crop rectangle normalized to 0..1 of the working bitmap's current width/height. */
private data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val FULL = NormRect(0f, 0f, 1f, 1f)
    }
}

private enum class CropCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

private data class NormPoint(val x: Float, val y: Float)

/** Which of the two mutually-exclusive crop shapes is active -- set automatically to match
 * whichever of the Crop/Free corners tabs the user is currently on (see the `LaunchedEffect(tab)`
 * in [PhotoEditScreen]), not chosen via an explicit pill anymore. [RECT] is the plain
 * axis-aligned/aspect-locked crop ([EditState.cropRect]). [FREE_CORNERS] uses the independently
 * -draggable-corner [CropQuad] interaction, cropping the photo (at its own natural proportions,
 * never stretched) into the quad's shape with transparency outside it (see [cropQuadTransparent]).
 * Perspective is no longer a crop shape of its own -- see [PerspectiveDepths] and
 * [depthAdjustedQuad] for how it now works as a separate post-crop warp instead. */
private enum class CropMode { RECT, FREE_CORNERS }

/** A general quadrilateral crop -- each corner moved independently rather than kept as an
 * axis-aligned rectangle. See [CropMode] for how [FREE_CORNERS] and [PERSPECTIVE] each use one of
 * these differently. */
private data class CropQuad(
    val topLeft: NormPoint,
    val topRight: NormPoint,
    val bottomLeft: NormPoint,
    val bottomRight: NormPoint,
) {
    companion object {
        fun fromRect(rect: NormRect) = CropQuad(
            NormPoint(rect.left, rect.top),
            NormPoint(rect.right, rect.top),
            NormPoint(rect.left, rect.bottom),
            NormPoint(rect.right, rect.bottom),
        )
    }
}

private enum class QuadCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

private data class TextSticker(
    val id: Long,
    val text: String,
    val xNorm: Float,
    val yNorm: Float,
    val colorArgb: Long,
    /** Null means no background behind the text at all. */
    val backgroundArgb: Long? = null,
    val textSizeSp: Float = 22f,
    /** Overall size multiplier for the whole sticker (text + background together), independent of
     * [textSizeSp] -- lets the sticker be scaled as a unit as well as having its own font size tuned. */
    val scale: Float = 1f,
    /** Free rotation, in degrees, set by dragging the sticker's own resize/rotate handle. */
    val rotationDegrees: Float = 0f,
)

/** Per-corner "how far this corner is pulled toward (positive) or pushed away from (negative) the
 * viewer", -100..100, 0 = no perspective effect. Unlike the old drag-based Perspective crop mode,
 * these never reposition a corner in 2D -- that's what the Crop/Free corners tabs are for -- they
 * only feed [depthAdjustedQuad], which the actual warp ([warpQuadToRect]) is applied against. */
private data class PerspectiveDepths(
    val topLeft: Float = 0f,
    val topRight: Float = 0f,
    val bottomLeft: Float = 0f,
    val bottomRight: Float = 0f,
) {
    val isIdentity: Boolean get() = topLeft == 0f && topRight == 0f && bottomLeft == 0f && bottomRight == 0f
}

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
    val cropMode: CropMode = CropMode.RECT,
    /** Non-null while [cropMode] is [CropMode.FREE_CORNERS] -- overrides [cropRect] for both the
     * on-screen overlay and the actual save. */
    val cropQuad: CropQuad? = null,
    /** Continuous fine-rotation ("straighten"), in degrees -- applied as a final step after
     * cropping, unlike the discrete 90-degree rotate button which rotates the whole working
     * bitmap up front instead. */
    val straightenDegrees: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val focus: FocusSpot = FocusSpot(),
    val stickers: List<TextSticker> = emptyList(),
    /** Applied as a post-crop warp -- see [PerspectiveDepths] and [depthAdjustedQuad]. */
    val perspectiveDepths: PerspectiveDepths = PerspectiveDepths(),
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
    // Sticky once true -- set whenever a bake (see bakeGeometry) leaves real transparency in
    // workingBitmap (a Free corners mask-crop, or a Perspective warp that pulls a corner beyond
    // the frame). EditState's own transform fields only describe what's still PENDING, so once a
    // transparent result is baked into the bitmap itself, this is the only remaining record that
    // the final save must use PNG -- otherwise a later, fully-opaque pending edit would make
    // saveEditedPhoto think JPEG is safe and flatten that already-baked transparency to black.
    var bitmapHasAlpha by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var showSaveChoiceDialog by remember { mutableStateOf(false) }

    // Nullable -- null means no tool panel is open (every panel's "Done" button, or tapping the
    // already-active tool's own dock icon, collapses back to this neutral state), distinct from
    // always having exactly one of the 5 tools' panels open.
    var tab by remember { mutableStateOf<EditTab?>(EditTab.CROP) }
    var current by remember { mutableStateOf(EditState()) }
    val undoStack = remember { mutableStateListOf<EditState>() }
    val redoStack = remember { mutableStateListOf<EditState>() }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var editingStickerId by remember { mutableStateOf<Long?>(null) }
    var nextStickerId by remember { mutableStateOf(1L) }
    var showOriginal by remember { mutableStateOf(false) }
    var adjustParamIndex by remember { mutableStateOf(0) }
    var selectedPerspectiveCorner by remember { mutableStateOf(QuadCorner.TOP_LEFT) }

    // Which crop shape is "active" follows whichever of the Crop/Free corners tabs is currently
    // open, rather than an explicit pill -- entering Free corners for the first time seeds its
    // quad from the current rect crop, exactly like the old pill toggle used to.
    LaunchedEffect(tab) {
        when (tab) {
            EditTab.CROP -> if (current.cropMode != CropMode.RECT) {
                current = current.copy(cropMode = CropMode.RECT)
            }
            EditTab.FREE_CORNERS -> if (current.cropMode != CropMode.FREE_CORNERS || current.cropQuad == null) {
                current = current.copy(
                    cropMode = CropMode.FREE_CORNERS,
                    cropQuad = current.cropQuad ?: CropQuad.fromRect(current.cropRect),
                )
            }
            else -> {}
        }
    }

    fun commit(newState: EditState) {
        undoStack.add(current)
        redoStack.clear()
        current = newState
    }

    // Crop/Free corners/Perspective were previously "pending until Save" like every other edit
    // here -- but unlike Adjust/Sticker, NOTHING in the live preview showed their effect at all
    // outside their own tab's overlay, so tapping that tab's Done (which just closes the panel)
    // looked exactly like the edit had been discarded. This instead actually applies the crop
    // shape + Perspective warp + straighten to workingBitmap right now, the same way the Rotate
    // 90 button already mutates workingBitmap directly -- so Done visibly does something, and
    // the photo shown afterward is the real result, not a promise redeemed only at Save.
    fun bakeGeometry() {
        val bitmap = workingBitmap ?: return
        // Entering Free corners auto-seeds cropQuad to the full-rect equivalent (see the
        // LaunchedEffect(tab) above) even before the user drags anything -- comparing cropQuad to
        // null would miss that and wrongly treat "opened the tab, changed nothing" as a real
        // pending crop, wiping stickers/adjust for no reason on every stray Done tap.
        val nothingPending = current.cropRect == NormRect.FULL &&
            (current.cropQuad == null || current.cropQuad == CropQuad.fromRect(NormRect.FULL)) &&
            current.perspectiveDepths.isIdentity &&
            current.straightenDegrees == 0f
        if (nothingPending) return
        val hadPendingTransparency = current.cropMode == CropMode.FREE_CORNERS ||
            !current.perspectiveDepths.isIdentity ||
            current.straightenDegrees % 90f != 0f
        // applyCrop already straightens (first) then crops (then warps) -- see its own doc comment.
        workingBitmap = applyCrop(bitmap, current)
        if (hadPendingTransparency) bitmapHasAlpha = true
        // Geometry (crop/quad/perspective/straighten) is now baked into the bitmap itself, so its
        // normalized coordinates are meaningless going forward -- reset exactly like Rotate 90
        // does. Color adjustments aren't tied to any coordinate space, so those alone carry over.
        commit(
            EditState(
                brightness = current.brightness,
                contrast = current.contrast,
                saturation = current.saturation,
                warmth = current.warmth,
                highlights = current.highlights,
                shadows = current.shadows,
            ),
        )
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

    // Sticker edits (text/size/color/rotation) live-update `current.stickers` directly, WITHOUT a
    // modal -- unlike the old StickerTextDialog, they're shown in the bottom panel below the photo
    // (see EditTab.STICKER's body) so the photo stays fully visible the whole time you're adjusting.
    fun updateSticker(id: Long, transform: (TextSticker) -> TextSticker) {
        current = current.copy(stickers = current.stickers.map { if (it.id == id) transform(it) else it })
    }

    // Deselecting (tapping elsewhere on the photo, tapping Done in the sticker panel, or leaving
    // via back) drops any sticker that was left with no actual text -- e.g. one just added via
    // "Add text" and then abandoned before typing anything -- instead of leaving an invisible
    // empty sticker behind.
    fun deselectSticker() {
        val id = editingStickerId ?: return
        val sticker = current.stickers.find { it.id == id }
        if (sticker != null && sticker.text.isBlank()) {
            commit(current.copy(stickers = current.stickers.filter { it.id != id }))
        }
        editingStickerId = null
    }

    // Without this, system back (button or gesture) always closed the whole editor immediately,
    // no matter what was open inside it -- a selected sticker mid-edit, or a tool tab other than
    // Transform. One back press now only steps out one level at a time (deselects the sticker, or
    // returns to Transform from another tab) before a further press actually leaves.
    BackHandler {
        when {
            editingStickerId != null -> deselectSticker()
            tab != null -> tab = null
            else -> onDone()
        }
    }

    LaunchedEffect(item.uri) {
        isLoading = true
        workingBitmap = loadDownsampledBitmap(context, item.uri, maxDimension = 2048)
        isLoading = false
        bitmapHasAlpha = false
        current = EditState()
        undoStack.clear()
        redoStack.clear()
        dragBaseline = null
        tab = EditTab.CROP
        selectedPerspectiveCorner = QuadCorner.TOP_LEFT
    }

    fun performSave(replace: Boolean) {
        val bitmap = workingBitmap ?: return
        val state = current
        val hasBakedAlpha = bitmapHasAlpha
        isSaving = true
        scope.launch {
            saveEditedPhoto(context, bitmap, state, item, replace, hasBakedAlpha)
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

    val panelBg = Color(0xFF1C1C1C)

    // What's actually shown/measured against everywhere the crop tools need a reference frame --
    // the working bitmap as-is normally, or a live rotated preview of it whenever a straighten is
    // pending (see the main preview Box below for why this replaced rotating the whole preview
    // Box, crop overlay included, as one rigid unit). Hoisted here (rather than only inside the
    // preview's own BoxWithConstraints) so the aspect-ratio pills below, which also need to know
    // this frame's actual proportions, see the same value.
    val straightenedPreview: Bitmap? = remember(workingBitmap, current.straightenDegrees) {
        val wb = workingBitmap
        if (wb != null && current.straightenDegrees != 0f) straightenPreview(wb, current.straightenDegrees) else wb
    }

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
                tab?.let { t ->
                    when (t) {
                        EditTab.CROP -> Column(Modifier.background(panelBg).padding(vertical = 16.dp)) {
                            ToolPanelHeader("Crop") { bakeGeometry(); tab = null }
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CropAspect.entries.forEach { a ->
                                    DarkPill(
                                        label = a.label,
                                        selected = current.cropAspect == a,
                                        onClick = {
                                            val reference = straightenedPreview
                                            val rect = if (a == CropAspect.FREE || reference == null) {
                                                NormRect.FULL
                                            } else {
                                                applyAspectLock(current.cropRect, a.ratio, reference.width, reference.height)
                                            }
                                            commit(current.copy(cropAspect = a, cropRect = rect))
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(18.dp))
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Color.White.copy(alpha = 0.12f))
                            Spacer(Modifier.height(16.dp))
                            RotateStraightenControls(
                                straightenDegrees = current.straightenDegrees,
                                onRotate90 = {
                                    workingBitmap?.let { bmp ->
                                        workingBitmap = rotateBitmap90(bmp)
                                        // A crop/sticker layout from before the rotation no longer lines
                                        // up with the new orientation, so this starts a fresh layout on it.
                                        commit(EditState())
                                    }
                                },
                                onStraightenLive = { mutateLive(current.copy(straightenDegrees = it)) },
                                onStraightenCommitFinished = { endLiveMutation() },
                                onStraightenTyped = { commit(current.copy(straightenDegrees = it)) },
                            )
                        }
                        EditTab.FREE_CORNERS -> Column(Modifier.background(panelBg).padding(vertical = 16.dp)) {
                            ToolPanelHeader("Free corners") { bakeGeometry(); tab = null }
                            Text(
                                "Drag a corner to reshape the selection. The area outside it will be transparent.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(Modifier.height(18.dp))
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Color.White.copy(alpha = 0.12f))
                            Spacer(Modifier.height(16.dp))
                            RotateStraightenControls(
                                straightenDegrees = current.straightenDegrees,
                                onRotate90 = {
                                    workingBitmap?.let { bmp ->
                                        workingBitmap = rotateBitmap90(bmp)
                                        commit(EditState())
                                    }
                                },
                                onStraightenLive = { mutateLive(current.copy(straightenDegrees = it)) },
                                onStraightenCommitFinished = { endLiveMutation() },
                                onStraightenTyped = { commit(current.copy(straightenDegrees = it)) },
                            )
                        }
                        EditTab.PERSPECTIVE -> Column(Modifier.background(panelBg).padding(vertical = 16.dp)) {
                            ToolPanelHeader("Perspective") { bakeGeometry(); tab = null }
                            Text(
                                "Select a corner, then use the slider to pull it toward or away from the screen. " +
                                    "Reposition the corners themselves in Crop or Free corners first.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                listOf(
                                    QuadCorner.TOP_LEFT to "Top L",
                                    QuadCorner.TOP_RIGHT to "Top R",
                                    QuadCorner.BOTTOM_LEFT to "Bot L",
                                    QuadCorner.BOTTOM_RIGHT to "Bot R",
                                ).forEach { (corner, label) ->
                                    DarkPill(
                                        label = label,
                                        selected = selectedPerspectiveCorner == corner,
                                        onClick = { selectedPerspectiveCorner = corner },
                                        compact = true,
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            val depths = current.perspectiveDepths
                            val selectedDepth = when (selectedPerspectiveCorner) {
                                QuadCorner.TOP_LEFT -> depths.topLeft
                                QuadCorner.TOP_RIGHT -> depths.topRight
                                QuadCorner.BOTTOM_LEFT -> depths.bottomLeft
                                QuadCorner.BOTTOM_RIGHT -> depths.bottomRight
                            }
                            fun withSelectedDepth(value: Float): PerspectiveDepths = when (selectedPerspectiveCorner) {
                                QuadCorner.TOP_LEFT -> depths.copy(topLeft = value)
                                QuadCorner.TOP_RIGHT -> depths.copy(topRight = value)
                                QuadCorner.BOTTOM_LEFT -> depths.copy(bottomLeft = value)
                                QuadCorner.BOTTOM_RIGHT -> depths.copy(bottomRight = value)
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Away", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                                Slider(
                                    value = selectedDepth,
                                    onValueChange = { mutateLive(current.copy(perspectiveDepths = withSelectedDepth(it))) },
                                    onValueChangeFinished = { endLiveMutation() },
                                    valueRange = -100f..100f,
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                )
                                Text("Toward", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                            }
                            if (!depths.isIdentity) {
                                TextButton(
                                    onClick = { commit(current.copy(perspectiveDepths = PerspectiveDepths())) },
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                ) {
                                    Text("Reset perspective")
                                }
                            }
                        }
                        EditTab.ADJUST -> Column(Modifier.background(panelBg)) {
                            ToolPanelHeader("Adjust", modifier = Modifier.padding(top = 16.dp)) { tab = null }
                            if (current.focus.strength > 0f) {
                                Text(
                                    "Drag the circle on the photo to move the spotlight.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            GestureAdjustPanel(
                                background = panelBg,
                                labels = listOf(
                                    "Brightness", "Contrast", "Saturation", "Warmth", "Highlights", "Shadows",
                                    "Focus strength", "Spotlight size",
                                ),
                                values = listOf(
                                    current.brightness, current.contrast, current.saturation,
                                    current.warmth, current.highlights, current.shadows,
                                    current.focus.strength, current.focus.radiusNorm * 100f,
                                ),
                                ranges = List(6) { -100f..100f } + listOf(0f..100f, 10f..80f),
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
                                            5 -> current.copy(shadows = value)
                                            6 -> current.copy(focus = current.focus.copy(strength = value.coerceIn(0f, 100f)))
                                            else -> current.copy(
                                                focus = current.focus.copy(radiusNorm = (value / 100f).coerceIn(0.1f, 0.8f)),
                                            )
                                        },
                                    )
                                },
                                onChangeFinished = { endLiveMutation() },
                            )
                        }
                        EditTab.STICKER -> Column(
                            Modifier.background(panelBg).fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            val selectedSticker = current.stickers.find { it.id == editingStickerId }
                            if (selectedSticker == null) {
                                ToolPanelHeader("Sticker") { tab = null }
                                TextButton(onClick = {
                                    val id = nextStickerId
                                    nextStickerId += 1
                                    commit(current.copy(stickers = current.stickers + TextSticker(id, "Text", 0.5f, 0.5f, 0xFFFFFFFFL)))
                                    editingStickerId = id
                                }) {
                                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Add text", color = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Tap a sticker on the photo to edit it -- drag it to move, or use its " +
                                        "corner handle to resize and rotate freely.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.6f),
                                )
                            } else {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    SectionLabel("Sticker")
                                    Row {
                                        IconButton(
                                            onClick = {
                                                commit(current.copy(stickers = current.stickers.filter { it.id != selectedSticker.id }))
                                                editingStickerId = null
                                            },
                                        ) {
                                            Icon(Icons.Filled.Close, contentDescription = "Delete sticker", tint = Color.White.copy(alpha = 0.8f))
                                        }
                                        TextButton(onClick = { deselectSticker() }) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Done", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = selectedSticker.text,
                                    onValueChange = { newText -> updateSticker(selectedSticker.id) { it.copy(text = newText) } },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Text") },
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Text size: ${selectedSticker.textSizeSp.roundToInt()}sp",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.8f),
                                )
                                Slider(
                                    value = selectedSticker.textSizeSp,
                                    onValueChange = { mutateLive(current.copy(stickers = current.stickers.map { s -> if (s.id == selectedSticker.id) s.copy(textSizeSp = it) else s })) },
                                    onValueChangeFinished = { endLiveMutation() },
                                    valueRange = 12f..64f,
                                )
                                Text(
                                    "Sticker size: ${String.format("%.1f", selectedSticker.scale)}x",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.8f),
                                )
                                Slider(
                                    value = selectedSticker.scale,
                                    onValueChange = { mutateLive(current.copy(stickers = current.stickers.map { s -> if (s.id == selectedSticker.id) s.copy(scale = it) else s })) },
                                    onValueChangeFinished = { endLiveMutation() },
                                    valueRange = 0.3f..5f,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("Text color", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                                Spacer(Modifier.height(4.dp))
                                ColorSwatchRow(
                                    selectedArgb = selectedSticker.colorArgb,
                                    onSelect = { c ->
                                        c?.let { commit(current.copy(stickers = current.stickers.map { s -> if (s.id == selectedSticker.id) s.copy(colorArgb = it) else s })) }
                                    },
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("Background", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                                Spacer(Modifier.height(4.dp))
                                ColorSwatchRow(
                                    selectedArgb = selectedSticker.backgroundArgb,
                                    includeNone = true,
                                    onSelect = { c ->
                                        commit(current.copy(stickers = current.stickers.map { s -> if (s.id == selectedSticker.id) s.copy(backgroundArgb = c) else s }))
                                    },
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    EditTab.entries.forEach { t ->
                        ToolDockButton(tab = t, selected = tab == t, onClick = { tab = if (tab == t) null else t })
                    }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val bitmap = workingBitmap
            when {
                isLoading -> CircularProgressIndicator(color = Color.White)
                bitmap == null -> Text("Couldn't load this photo.", color = Color.White)
                else -> {
                    val composeMatrix = buildColorMatrix(current)
                    val colorFilter = if (showOriginal) {
                        null
                    } else {
                        ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(composeMatrix.array))
                    }
                    // The crop box has to stay level on screen the whole time -- rotating it
                    // together with the photo (as a single rigid Box, via graphicsLayer) never
                    // actually straightens anything, since both tilt by the same amount and the
                    // photo looks exactly as crooked relative to the box as before. Instead, this
                    // shows a live ROTATED preview of the photo itself (canvas expanded so nothing
                    // is clipped, same principle as the final bake/save) and lets the crop box sit
                    // over it normally, un-rotated -- exactly like every other editor's straighten
                    // tool: a level frame, with the photo turning underneath it. straightenedPreview
                    // is hoisted above (shared with the aspect-ratio pills), downsampled for speed
                    // since it recomputes on every slider tick during a drag.
                    val displayBitmap = straightenedPreview ?: bitmap
                    // Sized explicitly to fit within BOTH available dimensions (not just
                    // Modifier.aspectRatio() off of the full width) -- for a photo tall/narrow
                    // enough that width-first sizing would make it taller than the space actually
                    // available here (between the top bar and the bottom tool panel), that alone
                    // could push part of the image, and the crop handles anchored to its edges,
                    // outside the visible screen entirely, which is exactly why some photos
                    // showed no visible/reachable crop corners while others did.
                    val photoRatio = displayBitmap.width.toFloat() / displayBitmap.height.toFloat()
                    val photoWidthDp: androidx.compose.ui.unit.Dp
                    val photoHeightDp: androidx.compose.ui.unit.Dp
                    if (maxWidth / photoRatio <= maxHeight) {
                        photoWidthDp = maxWidth
                        photoHeightDp = maxWidth / photoRatio
                    } else {
                        photoHeightDp = maxHeight
                        photoWidthDp = maxHeight * photoRatio
                    }
                    Box(
                        modifier = Modifier
                            .size(photoWidthDp, photoHeightDp)
                            .onSizeChanged { boxSize = it }
                            // Press and hold anywhere on the photo to instantly preview the untouched
                            // original -- release to go back to the edited version. A plain tap
                            // (not on a sticker -- those consume the touch first via their own
                            // pointerInput/clickable) deselects whichever sticker was selected.
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onPress = {
                                        showOriginal = true
                                        tryAwaitRelease()
                                        showOriginal = false
                                    },
                                    onTap = { deselectSticker() },
                                )
                            },
                    ) {
                        // While on the Perspective tab, the photo shown IS the actual warped
                        // result (recomputed live off a downsampled copy as the slider moves) --
                        // unlike a wireframe-only preview, this is the only way dragging the
                        // slider visibly does anything to the photo instead of just moving an
                        // abstract outline that never touched a single pixel. Built from
                        // displayBitmap (already straightened) with straightenDegrees zeroed out
                        // in the state passed in, so applyCrop doesn't rotate it a second time.
                        val perspectivePreview = if (tab == EditTab.PERSPECTIVE && !showOriginal) {
                            remember(displayBitmap, current.cropRect, current.cropQuad, current.cropMode, current.perspectiveDepths) {
                                buildPerspectivePreview(displayBitmap, current.copy(straightenDegrees = 0f))
                            }
                        } else {
                            null
                        }
                        Image(
                            bitmap = (perspectivePreview ?: displayBitmap).asImageBitmap(),
                            contentDescription = item.displayName,
                            contentScale = ContentScale.Fit,
                            colorFilter = colorFilter,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Stickers/focus are positioned relative to displayBitmap, uncropped
                        // (boxSize is that Image's own rendered size) -- while the Perspective tab
                        // is showing the warped/cropped preview above instead of that, overlaying
                        // them would float in the wrong place relative to it, so they're hidden
                        // for that tab only (same principle as each tab already only showing its
                        // own relevant overlay).
                        if (!showOriginal && tab != EditTab.PERSPECTIVE) {
                            if (current.focus.strength > 0f) {
                                FocusOverlay(current.focus)
                            }
                            current.stickers.forEach { sticker ->
                                val isSelected = sticker.id == editingStickerId
                                Text(
                                    sticker.text,
                                    color = Color(sticker.colorArgb),
                                    fontSize = sticker.textSizeSp.sp,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .normOffset(sticker.xNorm, sticker.yNorm, boxSize, density)
                                        .graphicsLayer(
                                            scaleX = sticker.scale,
                                            scaleY = sticker.scale,
                                            rotationZ = sticker.rotationDegrees,
                                        )
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
                                        .clickable {
                                            editingStickerId = sticker.id
                                            tab = EditTab.STICKER
                                        }
                                        .background(sticker.backgroundArgb?.let { Color(it) } ?: Color.Transparent)
                                        .then(
                                            if (isSelected) {
                                                Modifier.border(1.dp, Color.White.copy(alpha = 0.8f))
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            // The selected sticker's own resize+rotate handle -- dragging it changes
                            // both the sticker's distance from this handle's rest position (-> scale)
                            // and the angle between them (-> rotation) at once, the standard
                            // "corner handle" gesture from most sticker/text editors, instead of only
                            // being adjustable via the size slider in the bottom panel.
                            current.stickers.firstOrNull { it.id == editingStickerId }?.let { sticker ->
                                val handleDistancePx = with(density) { 56.dp.toPx() }
                                Box(
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .offset {
                                            val baseX = sticker.xNorm * boxSize.width
                                            val baseY = sticker.yNorm * boxSize.height
                                            val angleRad = Math.toRadians(sticker.rotationDegrees.toDouble())
                                            val dist = handleDistancePx * sticker.scale
                                            IntOffset(
                                                (baseX + dist * cos(angleRad)).roundToInt(),
                                                (baseY + dist * sin(angleRad)).roundToInt(),
                                            )
                                        }
                                        .size(32.dp)
                                        .pointerInput(sticker.id) {
                                            detectDragImmediate(onDragEnd = { endLiveMutation() }) { change, dragAmount ->
                                                change.consume()
                                                val latest = current.stickers.firstOrNull { it.id == sticker.id } ?: return@detectDragImmediate
                                                val angleRad = Math.toRadians(latest.rotationDegrees.toDouble())
                                                val dist = handleDistancePx * latest.scale
                                                val curX = dist * cos(angleRad)
                                                val curY = dist * sin(angleRad)
                                                val newX = curX + dragAmount.x
                                                val newY = curY + dragAmount.y
                                                val newDist = sqrt(newX * newX + newY * newY)
                                                val newScale = (newDist / handleDistancePx).toFloat().coerceIn(0.3f, 5f)
                                                val newRotation = Math.toDegrees(atan2(newY, newX)).toFloat()
                                                mutateLive(
                                                    current.copy(
                                                        stickers = current.stickers.map {
                                                            if (it.id == sticker.id) it.copy(scale = newScale, rotationDegrees = newRotation) else it
                                                        },
                                                    ),
                                                )
                                            }
                                        }
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                        .border(2.dp, Color.White, CircleShape),
                                )
                            }
                            when (tab) {
                                EditTab.CROP -> {
                                    CropOverlay(
                                        rect = current.cropRect,
                                        boxSize = boxSize,
                                        density = density,
                                        onCornerDrag = { corner, dxNorm, dyNorm ->
                                            var rect = updatedCropRect(current.cropRect, corner, dxNorm, dyNorm)
                                            if (current.cropAspect != CropAspect.FREE && displayBitmap.width > 0 && displayBitmap.height > 0) {
                                                rect = applyAspectLock(rect, current.cropAspect.ratio, displayBitmap.width, displayBitmap.height)
                                            }
                                            mutateLive(current.copy(cropRect = rect))
                                        },
                                        onMoveDrag = { dxNorm, dyNorm ->
                                            mutateLive(current.copy(cropRect = translatedRect(current.cropRect, dxNorm, dyNorm)))
                                        },
                                        onDragEnd = { endLiveMutation() },
                                    )
                                }
                                EditTab.FREE_CORNERS -> {
                                    val quad = current.cropQuad ?: CropQuad.fromRect(current.cropRect)
                                    FreeCornersOverlay(
                                        quad = quad,
                                        boxSize = boxSize,
                                        density = density,
                                        onCornerDrag = { corner, dxNorm, dyNorm ->
                                            // Reads current.cropQuad fresh (a property-delegate
                                            // getter, always up to date) rather than the `quad`
                                            // local captured above -- FreeCornersOverlay's own
                                            // pointerInput coroutine, once launched, keeps calling
                                            // THIS SAME closure across many recompositions without
                                            // restarting (it's keyed on boxSize/corner, not on
                                            // quad), so a captured `quad` would stay frozen at
                                            // whatever it was on the very first drag tick --  every
                                            // subsequent tick would then recompute from that same
                                            // stale starting point instead of from wherever the
                                            // corner actually is now, which is exactly why dragging
                                            // visibly moved the corner a little and then snapped it
                                            // back instead of following the finger.
                                            val latestQuad = current.cropQuad ?: quad
                                            mutateLive(current.copy(cropQuad = updatedCropQuad(latestQuad, corner, dxNorm, dyNorm)))
                                        },
                                        onMoveDrag = { dxNorm, dyNorm ->
                                            val latestQuad = current.cropQuad ?: quad
                                            mutateLive(current.copy(cropQuad = translatedQuad(latestQuad, dxNorm, dyNorm)))
                                        },
                                        onDragEnd = { endLiveMutation() },
                                    )
                                }
                                // No overlay for Perspective -- the Image above already shows the
                                // live-warped result directly, so there's no separate wireframe to
                                // draw on top of it (corner selection happens via the bottom
                                // panel's Top L/Top R/Bot L/Bot R pills instead).
                                else -> {}
                            }
                            if (tab == EditTab.ADJUST && current.focus.strength > 0f) {
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

/** [compact] shrinks padding/text so 4+ of these fit on one row on a phone-width screen without
 * needing to scroll horizontally to reach the last one (used by the Perspective tab's 4 corner
 * pills -- previously at full size they overflowed off-screen). */
@Composable
private fun DarkPill(label: String, selected: Boolean, onClick: () -> Unit, compact: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 14.dp, vertical = if (compact) 6.dp else 8.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.Black else Color.White,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
        )
    }
}

/** A small caps-style heading above a group of controls in the bottom panel -- gives each section
 * (crop shape, straighten, etc.) a visible name instead of leaving related controls to just run
 * together with nothing marking where one group ends and the next begins. */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.85f),
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

/** Every tool's panel starts with its own name plus an explicit "Done" button -- confirming and
 * collapsing that particular tool's controls, distinct from the top bar's overall "Save": tapping
 * Done here just closes this panel (see [PhotoEditScreen]'s nullable `tab`) so the next tool can be
 * opened without disturbing whatever was just changed in this one. */
@Composable
private fun ToolPanelHeader(title: String, modifier: Modifier = Modifier, onDone: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(title)
        TextButton(onClick = onDone) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("Done", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
    Spacer(Modifier.height(10.dp))
}

/** The 90°-rotate button plus the (full-range, typeable) straighten control -- duplicated
 * identically in both the Crop and Free corners tabs (each keeps its own copy of this section, per
 * the user's request that both be independently available rather than shared/hidden between the
 * two tabs). */
@Composable
private fun RotateStraightenControls(
    straightenDegrees: Float,
    onRotate90: () -> Unit,
    onStraightenLive: (Float) -> Unit,
    onStraightenCommitFinished: () -> Unit,
    onStraightenTyped: (Float) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onRotate90) {
            Icon(Icons.Filled.RotateRight, contentDescription = "Rotate 90°", tint = Color.White)
        }
        Spacer(Modifier.width(4.dp))
        SectionLabel("Straighten", modifier = Modifier.weight(1f))
        if (straightenDegrees != 0f) {
            IconButton(onClick = { onStraightenTyped(0f) }) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Reset straighten",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Slider(
            value = straightenDegrees,
            onValueChange = onStraightenLive,
            onValueChangeFinished = onStraightenCommitFinished,
            valueRange = -180f..180f,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        var text by remember { mutableStateOf(straightenDegrees.roundToInt().toString()) }
        // Syncs from the slider/reset/rotate whenever they change the value out from under this
        // field, but not on every recomposition -- otherwise a keystroke that hasn't yet rounded to
        // a value different from what's already committed would get clobbered mid-type.
        LaunchedEffect(straightenDegrees) {
            val parsedRounded = text.toFloatOrNull()?.roundToInt()
            if (parsedRounded != straightenDegrees.roundToInt()) {
                text = straightenDegrees.roundToInt().toString()
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { new ->
                text = new
                new.toFloatOrNull()?.let { onStraightenTyped(it.coerceIn(-180f, 180f)) }
            },
            modifier = Modifier.width(76.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.labelLarge.copy(color = Color.White, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

/**
 * The fine-tune control for Adjust: a horizontally scrollable row of clearly labeled chips picks
 * which parameter is active (tap one directly -- unambiguous about which is which, unlike an
 * earlier version of this control that used a row of small unlabeled dots, which were both hard
 * to hit and gave no indication of which parameter each one even was), then drag up/down
 * anywhere on the number below to change that parameter's value, reading the live number as you
 * drag.
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
    var dragAreaHeightPx by remember { mutableStateOf(1f) }
    val label = labels.getOrElse(selectedIndex) { "" }
    val value = values.getOrElse(selectedIndex) { 0f }
    // pointerInput below is keyed only on selectedIndex, not on `values` itself -- restarting it
    // on every value tick would abort an in-progress drag. That means its coroutine can outlive
    // several recompositions, so `values` must be read through rememberUpdatedState: otherwise a
    // second drag on the same parameter (no index switch in between) would compute its starting
    // point from whatever `values` was when the coroutine last (re)launched, not the value the
    // first drag actually left it at.
    val latestValues = rememberUpdatedState(values)

    Column(Modifier.fillMaxWidth().background(background).padding(vertical = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            labels.indices.forEach { i -> DarkPill(label = labels[i], selected = i == selectedIndex, onClick = { onSelect(i) }) }
        }
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { dragAreaHeightPx = it.height.toFloat().coerceAtLeast(1f) }
                .pointerInput(selectedIndex) {
                    var accDy = 0f
                    var startValue = 0f
                    detectDragGestures(
                        onDragStart = {
                            accDy = 0f
                            startValue = latestValues.value.getOrElse(selectedIndex) { 0f }
                        },
                        onDragEnd = { onChangeFinished() },
                        onDragCancel = { onChangeFinished() },
                    ) { change, dragAmount ->
                        change.consume()
                        accDy += dragAmount.y
                        val range = ranges.getOrElse(selectedIndex) { -100f..100f }
                        val span = range.endInclusive - range.start
                        // Dragging UP increases the value (like a vertical slider) -- dragAmount.y
                        // is positive moving down, hence the negation. Scaled against this drag
                        // area's own height (not the full panel width) since it's now a purely
                        // vertical gesture with nothing horizontal to compare it to.
                        val delta = -accDy / (dragAreaHeightPx * 3f) * span
                        onChange(selectedIndex, (startValue + delta).coerceIn(range.start, range.endInclusive))
                    }
                }
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
            Text(
                value.roundToInt().toString(),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
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
    onMoveDrag: (Float, Float) -> Unit,
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
    // A finger placed anywhere INSIDE the crop rect (not on a corner handle) drags the whole
    // selection around over the photo, instead of only being able to resize it from a corner --
    // composed before the corner handles below, so a touch that lands on an actual corner circle
    // still hits that (smaller, on-top) handle first rather than this larger region underneath it.
    Box(
        Modifier
            .offset {
                IntOffset((rect.left * boxSize.width).roundToInt(), (rect.top * boxSize.height).roundToInt())
            }
            .size(
                width = with(density) { ((rect.right - rect.left) * boxSize.width).toDp() },
                height = with(density) { ((rect.bottom - rect.top) * boxSize.height).toDp() },
            )
            .pointerInput(boxSize) {
                detectDragImmediate(onDragEnd = { onDragEnd() }) { change, dragAmount ->
                    change.consume()
                    if (boxSize.width > 0 && boxSize.height > 0) {
                        onMoveDrag(dragAmount.x / boxSize.width, dragAmount.y / boxSize.height)
                    }
                }
            },
    )
    // Invisible -- only the bracket drawn above marks the corner now; this Box exists purely as
    // the same-size (28dp) touch target the visible circle used to occupy, so removing the circle
    // doesn't shrink how forgiving dragging from the corner is.
    Box(
        Modifier
            .normOffset(rect.left, rect.top, boxSize, density, centerOnPointDp = 40.dp)
            .size(28.dp)
            .pointerInput(boxSize) {
                detectDragImmediate(onDragEnd = { onDragEnd() }) { change, dragAmount ->
                    change.consume()
                    if (boxSize.width > 0 && boxSize.height > 0) {
                        onCornerDrag(CropCorner.TOP_LEFT, dragAmount.x / boxSize.width, dragAmount.y / boxSize.height)
                    }
                }
            },
    )
    Box(
        Modifier
            .normOffset(rect.right, rect.top, boxSize, density, centerOnPointDp = 40.dp)
            .size(28.dp)
            .pointerInput(boxSize) {
                detectDragImmediate(onDragEnd = { onDragEnd() }) { change, dragAmount ->
                    change.consume()
                    if (boxSize.width > 0 && boxSize.height > 0) {
                        onCornerDrag(CropCorner.TOP_RIGHT, dragAmount.x / boxSize.width, dragAmount.y / boxSize.height)
                    }
                }
            },
    )
    Box(
        Modifier
            .normOffset(rect.left, rect.bottom, boxSize, density, centerOnPointDp = 40.dp)
            .size(28.dp)
            .pointerInput(boxSize) {
                detectDragImmediate(onDragEnd = { onDragEnd() }) { change, dragAmount ->
                    change.consume()
                    if (boxSize.width > 0 && boxSize.height > 0) {
                        onCornerDrag(CropCorner.BOTTOM_LEFT, dragAmount.x / boxSize.width, dragAmount.y / boxSize.height)
                    }
                }
            },
    )
    Box(
        Modifier
            .normOffset(rect.right, rect.bottom, boxSize, density, centerOnPointDp = 40.dp)
            .size(28.dp)
            .pointerInput(boxSize) {
                detectDragImmediate(onDragEnd = { onDragEnd() }) { change, dragAmount ->
                    change.consume()
                    if (boxSize.width > 0 && boxSize.height > 0) {
                        onCornerDrag(CropCorner.BOTTOM_RIGHT, dragAmount.x / boxSize.width, dragAmount.y / boxSize.height)
                    }
                }
            },
    )
}

/**
 * The "Free corners" crop overlay: draws the quad's own outline (a general quadrilateral, not
 * necessarily a rectangle) and one independently-draggable handle per corner -- unlike
 * [CropOverlay]'s corners, moving one here never affects the others. Lets an arbitrarily-shaped
 * (not just rectangular) region be cropped out -- see [CropQuad] and [cropQuadTransparent].
 */
@Composable
private fun FreeCornersOverlay(
    quad: CropQuad,
    boxSize: IntSize,
    density: androidx.compose.ui.unit.Density,
    onCornerDrag: (QuadCorner, Float, Float) -> Unit,
    onMoveDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        fun toPx(p: NormPoint) = Offset(p.x * size.width, p.y * size.height)
        val tl = toPx(quad.topLeft)
        val tr = toPx(quad.topRight)
        val bl = toPx(quad.bottomLeft)
        val br = toPx(quad.bottomRight)
        val path = Path().apply {
            moveTo(tl.x, tl.y)
            lineTo(tr.x, tr.y)
            lineTo(br.x, br.y)
            lineTo(bl.x, bl.y)
            close()
        }
        // Dims everything outside the quad -- previously this overlay only drew the outline
        // itself with no scrim at all, which (especially against a busy or light-colored photo)
        // made the quad's own edges hard to see and the selection hard to judge, unlike
        // CropOverlay's rectangular crop, which already dims outside its own selection.
        val outside = Path().apply { op(Path().apply { addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size)) }, path, PathOperation.Difference) }
        drawPath(outside, color = Color.Black.copy(alpha = 0.55f))
        drawPath(path, color = Color.White, style = Stroke(width = 2.dp.toPx()))
        // Corner brackets, drawn INWARD along the quad's own two edges meeting at each corner --
        // guaranteed to stay inside the quad regardless of its shape, unlike the draggable circle
        // handles below (centered exactly ON the corner point), which are barely visible whenever
        // a corner sits right at the image's own edge -- the starting state before any corner has
        // been dragged, since a fresh "Free corners" quad starts out matching the full rectangle.
        // Same fix as CropOverlay's own identical bracket for the axis-aligned rectangle case.
        val bracketLen = 22.dp.toPx()
        fun DrawScope.bracketToward(from: Offset, toward: Offset) {
            val dx = toward.x - from.x
            val dy = toward.y - from.y
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1f) return
            val end = Offset(from.x + dx / len * minOf(bracketLen, len), from.y + dy / len * minOf(bracketLen, len))
            drawLine(Color.White, from, end, strokeWidth = 4.dp.toPx())
        }
        bracketToward(tl, tr)
        bracketToward(tl, bl)
        bracketToward(tr, tl)
        bracketToward(tr, br)
        bracketToward(bl, tl)
        bracketToward(bl, br)
        bracketToward(br, tr)
        bracketToward(br, bl)
    }
    // Same "drag anywhere inside to move the whole selection" region as CropOverlay -- for a
    // general (non-rectangular) quad this uses its axis-aligned bounding box rather than the
    // exact quad shape, a reasonable approximation that's far simpler than hit-testing the
    // quad's own path, and composed before the corner handles so a touch on an actual corner
    // still reaches that (smaller, on-top) handle first.
    run {
        val xs = listOf(quad.topLeft.x, quad.topRight.x, quad.bottomLeft.x, quad.bottomRight.x)
        val ys = listOf(quad.topLeft.y, quad.topRight.y, quad.bottomLeft.y, quad.bottomRight.y)
        val minX = xs.min()
        val minY = ys.min()
        val maxX = xs.max()
        val maxY = ys.max()
        Box(
            Modifier
                .offset { IntOffset((minX * boxSize.width).roundToInt(), (minY * boxSize.height).roundToInt()) }
                .size(
                    width = with(density) { ((maxX - minX) * boxSize.width).toDp() },
                    height = with(density) { ((maxY - minY) * boxSize.height).toDp() },
                )
                .pointerInput(boxSize) {
                    detectDragImmediate(onDragEnd = { onDragEnd() }) { change, dragAmount ->
                        change.consume()
                        if (boxSize.width > 0 && boxSize.height > 0) {
                            onMoveDrag(dragAmount.x / boxSize.width, dragAmount.y / boxSize.height)
                        }
                    }
                },
        )
    }
    listOf(
        QuadCorner.TOP_LEFT to quad.topLeft,
        QuadCorner.TOP_RIGHT to quad.topRight,
        QuadCorner.BOTTOM_LEFT to quad.bottomLeft,
        QuadCorner.BOTTOM_RIGHT to quad.bottomRight,
    ).forEach { (corner, point) ->
        Box(
            Modifier
                .normOffset(point.x, point.y, boxSize, density, centerOnPointDp = 40.dp)
                .size(28.dp)
                .pointerInput(boxSize, corner) {
                    detectDragImmediate(onDragEnd = { onDragEnd() }) { change, dragAmount ->
                        change.consume()
                        if (boxSize.width > 0 && boxSize.height > 0) {
                            onCornerDrag(corner, dragAmount.x / boxSize.width, dragAmount.y / boxSize.height)
                        }
                    }
                },
        )
    }
}

/** Applies each of [depths]' per-corner "toward/away from the screen" values to [base]'s
 * corresponding corner, scaling it toward or away from the quad's own centroid -- positive depth
 * (toward the viewer) pushes a corner out beyond its original position, negative (away) pulls it
 * in toward the center. [base] is always the already-cropped bitmap's own unit square here (see
 * [applyCrop] and [buildPerspectivePreview]); the actual pixel warp is [warpQuadToRect]. */
private fun depthAdjustedQuad(base: CropQuad, depths: PerspectiveDepths): CropQuad {
    val cx = (base.topLeft.x + base.topRight.x + base.bottomLeft.x + base.bottomRight.x) / 4f
    val cy = (base.topLeft.y + base.topRight.y + base.bottomLeft.y + base.bottomRight.y) / 4f
    fun pulled(p: NormPoint, depth: Float): NormPoint {
        val factor = 1f + (depth / 100f) * 0.5f
        return NormPoint(cx + (p.x - cx) * factor, cy + (p.y - cy) * factor)
    }
    return CropQuad(
        topLeft = pulled(base.topLeft, depths.topLeft),
        topRight = pulled(base.topRight, depths.topRight),
        bottomLeft = pulled(base.bottomLeft, depths.bottomLeft),
        bottomRight = pulled(base.bottomRight, depths.bottomRight),
    )
}

private fun updatedCropQuad(quad: CropQuad, corner: QuadCorner, dxNorm: Float, dyNorm: Float): CropQuad {
    fun moved(p: NormPoint) = NormPoint((p.x + dxNorm).coerceIn(0f, 1f), (p.y + dyNorm).coerceIn(0f, 1f))
    return when (corner) {
        QuadCorner.TOP_LEFT -> quad.copy(topLeft = moved(quad.topLeft))
        QuadCorner.TOP_RIGHT -> quad.copy(topRight = moved(quad.topRight))
        QuadCorner.BOTTOM_LEFT -> quad.copy(bottomLeft = moved(quad.bottomLeft))
        QuadCorner.BOTTOM_RIGHT -> quad.copy(bottomRight = moved(quad.bottomRight))
    }
}

/** Shifts every corner of [quad] by the same amount -- moving the whole selection over the photo
 * rather than resizing it -- clamped so the quad's own bounding box never leaves the 0..1 image
 * bounds (each axis independently, so sliding it right, say, doesn't get vetoed by it already
 * being at its top edge). */
private fun translatedQuad(quad: CropQuad, dxNorm: Float, dyNorm: Float): CropQuad {
    val xs = listOf(quad.topLeft.x, quad.topRight.x, quad.bottomLeft.x, quad.bottomRight.x)
    val ys = listOf(quad.topLeft.y, quad.topRight.y, quad.bottomLeft.y, quad.bottomRight.y)
    val clampedDx = dxNorm.coerceIn(-xs.min(), 1f - xs.max())
    val clampedDy = dyNorm.coerceIn(-ys.min(), 1f - ys.max())
    fun moved(p: NormPoint) = NormPoint(p.x + clampedDx, p.y + clampedDy)
    return CropQuad(moved(quad.topLeft), moved(quad.topRight), moved(quad.bottomLeft), moved(quad.bottomRight))
}

/** The quad's axis-aligned bounding box -- used as an approximation of "the crop rect" for
 * positioning stickers/focus relative to a perspective-warped output (see [saveEditedPhoto]),
 * since those overlays aren't themselves warped through the same projective transform. */
private fun boundingRectOf(quad: CropQuad): NormRect {
    val xs = listOf(quad.topLeft.x, quad.topRight.x, quad.bottomLeft.x, quad.bottomRight.x)
    val ys = listOf(quad.topLeft.y, quad.topRight.y, quad.bottomLeft.y, quad.bottomRight.y)
    return NormRect(xs.min(), ys.min(), xs.max(), ys.max())
}

/**
 * Crops [quad] (its 4 corners, normalized 0..1 within [source]) out of [source] at its own
 * natural resolution -- never stretched or perspective-warped, unlike a straighten/keystone-style
 * quad crop -- by taking the quad's axis-aligned bounding box as the output canvas, leaving it
 * fully transparent, then drawing [source] (shifted so it lines up) clipped to the quad's own
 * shape. The result: the quad's interior shows the photo at its real proportions, and whatever's
 * between the quad and its bounding box (e.g. the corners of a diamond-ish quad) is transparent.
 * The output bitmap keeps this alpha channel -- see [saveEditedPhoto]'s PNG-vs-JPEG choice.
 */
private fun cropQuadTransparent(source: Bitmap, quad: CropQuad): Bitmap {
    val w = source.width.toFloat()
    val h = source.height.toFloat()
    val tl = Offset(quad.topLeft.x * w, quad.topLeft.y * h)
    val tr = Offset(quad.topRight.x * w, quad.topRight.y * h)
    val bl = Offset(quad.bottomLeft.x * w, quad.bottomLeft.y * h)
    val br = Offset(quad.bottomRight.x * w, quad.bottomRight.y * h)

    val minX = minOf(tl.x, tr.x, bl.x, br.x)
    val minY = minOf(tl.y, tr.y, bl.y, br.y)
    val maxX = maxOf(tl.x, tr.x, bl.x, br.x)
    val maxY = maxOf(tl.y, tr.y, bl.y, br.y)
    val outW = (maxX - minX).roundToInt().coerceAtLeast(1)
    val outH = (maxY - minY).roundToInt().coerceAtLeast(1)

    // A freshly created ARGB_8888 bitmap starts out fully transparent (all-zero) already -- no
    // drawColor() call needed, unlike the old background-color version of this function.
    val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)

    val path = android.graphics.Path().apply {
        moveTo(tl.x - minX, tl.y - minY)
        lineTo(tr.x - minX, tr.y - minY)
        lineTo(br.x - minX, br.y - minY)
        lineTo(bl.x - minX, bl.y - minY)
        close()
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.save()
    canvas.clipPath(path)
    canvas.drawBitmap(source, -minX, -minY, paint)
    canvas.restore()
    return output
}

/**
 * Warps the quadrilateral [quad] (its 4 corners, normalized 0..1 within [source]) onto a
 * straightened rectangle via a true projective transform -- Matrix.setPolyToPoly with 4 point
 * pairs performs actual perspective correction, not just an affine skew -- which is what pulling a
 * single corner toward or away from the viewer simulates: correcting (or deliberately adding)
 * keystone distortion, like a photo of a document or whiteboard shot at an angle. Unlike
 * [cropQuadTransparent], the whole output rectangle ends up fully opaque -- there's no "outside
 * the quad" left over once every pixel has been stretched to fill it. Output size is derived from
 * the quad's own average edge lengths in source pixels, so a wide, shallow quad still maps to a
 * roughly similarly-proportioned (now rectangular) output.
 */
private fun warpQuadToRect(source: Bitmap, quad: CropQuad): Bitmap {
    val w = source.width.toFloat()
    val h = source.height.toFloat()
    val tl = floatArrayOf(quad.topLeft.x * w, quad.topLeft.y * h)
    val tr = floatArrayOf(quad.topRight.x * w, quad.topRight.y * h)
    val bl = floatArrayOf(quad.bottomLeft.x * w, quad.bottomLeft.y * h)
    val br = floatArrayOf(quad.bottomRight.x * w, quad.bottomRight.y * h)

    fun dist(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        return sqrt(dx * dx + dy * dy)
    }
    val outW = (((dist(tl, tr) + dist(bl, br)) / 2f).roundToInt()).coerceAtLeast(1)
    val outH = (((dist(tl, bl) + dist(tr, br)) / 2f).roundToInt()).coerceAtLeast(1)

    val src = floatArrayOf(tl[0], tl[1], tr[0], tr[1], bl[0], bl[1], br[0], br[1])
    val dst = floatArrayOf(0f, 0f, outW.toFloat(), 0f, 0f, outH.toFloat(), outW.toFloat(), outH.toFloat())
    val matrix = Matrix()
    matrix.setPolyToPoly(src, 0, dst, 0, 4)

    val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(source, matrix, paint)
    return output
}

/**
 * Rotates [bitmap] by an arbitrary [degrees] (the "straighten" slider), unlike [rotateBitmap90]'s
 * fixed quarter-turn -- the output canvas expands to fit the rotated rectangle so no corner of the
 * original photo is clipped, which leaves 4 transparent triangular corners around the rotated
 * content whenever [degrees] isn't a multiple of 90 (see [saveEditedPhoto]'s PNG-vs-JPEG choice).
 */
private fun rotateBitmapArbitrary(bitmap: Bitmap, degrees: Float): Bitmap {
    if (degrees == 0f) return bitmap
    val matrix = Matrix().apply { postRotate(degrees) }
    val rotatedBounds = android.graphics.RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    matrix.mapRect(rotatedBounds)
    val outW = rotatedBounds.width().roundToInt().coerceAtLeast(1)
    val outH = rotatedBounds.height().roundToInt().coerceAtLeast(1)

    val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    val centeredMatrix = Matrix(matrix).apply { postTranslate(-rotatedBounds.left, -rotatedBounds.top) }
    canvas.drawBitmap(bitmap, centeredMatrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    return output
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

/** Shifts the whole rect by the same amount on both axes -- moving the selection over the photo
 * rather than resizing it -- clamped so it never leaves the 0..1 image bounds. */
private fun translatedRect(rect: NormRect, dxNorm: Float, dyNorm: Float): NormRect {
    val width = rect.right - rect.left
    val height = rect.bottom - rect.top
    val newLeft = (rect.left + dxNorm).coerceIn(0f, 1f - width)
    val newTop = (rect.top + dyNorm).coerceIn(0f, 1f - height)
    return NormRect(newLeft, newTop, newLeft + width, newTop + height)
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

/** A row of tappable color swatches -- [AccentColor]'s hand-picked palette, one per family, reused
 * here for the sticker's text/background color pickers. [includeNone] prepends a transparent
 * "no color" swatch (only meaningful for the background picker, where null means no background at
 * all); [selectedArgb] null with [includeNone] false simply means nothing currently matches. */
@Composable
private fun ColorSwatchRow(selectedArgb: Long?, includeNone: Boolean = false, onSelect: (Long?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (includeNone) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent)
                    .border(1.dp, Color.Gray, CircleShape)
                    .clickable { onSelect(null) },
                contentAlignment = Alignment.Center,
            ) {
                if (selectedArgb == null) {
                    Icon(Icons.Filled.Close, contentDescription = "None", tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
            }
        }
        AccentColor.entries.forEach { c ->
            val selected = selectedArgb == c.seed
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(c.seed))
                    .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                    .clickable { onSelect(c.seed) },
            )
        }
    }
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
    return result
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

/**
 * The crop step -- see [CropMode] for what each of the two shapes does -- followed by the
 * Perspective tab's depth-based warp, if any of its corners have been pulled off zero. That warp
 * operates on the ALREADY-cropped bitmap's own unit square (its 4 real corners), displaced per
 * [state.perspectiveDepths] and then mapped back onto a plain rectangle via [warpQuadToRect] --
 * the same "pull a corner to add/correct keystone" mechanism the old drag-based Perspective crop
 * mode used, just fed a synthetic quad from depth sliders instead of a dragged one.
 */
private fun applyCrop(bitmap: Bitmap, state: EditState): Bitmap {
    // Straighten happens FIRST, not after cropping -- the crop rect/quad the user drew was always
    // relative to what was actually on screen at the time, which (see the main preview's
    // straightenedPreview) is the already-straightened, canvas-expanded frame, not the original
    // un-rotated bitmap. Cropping that first and rotating afterward (the old order) is also just
    // wrong on its own terms: it rotates the ALREADY-cropped rectangle in place, which crops
    // corners off rather than ever straightening a tilted horizon within a level frame.
    val straightened = if (state.straightenDegrees != 0f) rotateBitmapArbitrary(bitmap, state.straightenDegrees) else bitmap
    val cropped = if (state.cropMode == CropMode.FREE_CORNERS) {
        val quad = state.cropQuad ?: CropQuad.fromRect(state.cropRect)
        cropQuadTransparent(straightened, quad)
    } else {
        val cropRect = cropRectFor(straightened.width, straightened.height, state.cropRect)
        Bitmap.createBitmap(straightened, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
    }
    return if (!state.perspectiveDepths.isIdentity) {
        val unitSquare = CropQuad.fromRect(NormRect.FULL)
        warpQuadToRect(cropped, depthAdjustedQuad(unitSquare, state.perspectiveDepths))
    } else {
        cropped
    }
}

/** Downsamples [source] and rotates it by [degrees] (canvas expanded, same as [rotateBitmapArbitrary])
 * for a fast live preview while dragging the straighten slider -- see the main preview's
 * straightenedPreview for why the crop box needs this shown as the photo itself, rather than
 * rotating the whole preview (crop box included) as a rigid unit. */
private fun straightenPreview(source: Bitmap, degrees: Float, maxDimension: Int = 1000): Bitmap {
    val scale = maxDimension.toFloat() / maxOf(source.width, source.height)
    val downsampled = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    } else {
        source
    }
    return rotateBitmapArbitrary(downsampled, degrees)
}

/**
 * The Perspective tab's live preview: the exact same crop-then-warp pipeline as [applyCrop] (so
 * what's shown while dragging the depth slider matches what Save actually produces), run against
 * a downsampled copy of [source] instead of the full-resolution working bitmap so it stays fast
 * enough to recompute on every slider tick. [maxDimension] is small since this is only ever shown
 * scaled down to fit the screen anyway.
 */
private fun buildPerspectivePreview(source: Bitmap, state: EditState, maxDimension: Int = 640): Bitmap {
    val scale = maxDimension.toFloat() / maxOf(source.width, source.height)
    val downsampled = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    } else {
        source
    }
    return applyCrop(downsampled, state)
}

private suspend fun saveEditedPhoto(
    context: Context,
    bitmap: Bitmap,
    state: EditState,
    original: MediaItem,
    replace: Boolean,
    hasBakedAlpha: Boolean,
) = withContext(Dispatchers.IO) {
    val cropped = applyCrop(bitmap, state)
    // Stickers/focus below are positioned relative to this rect -- exact for a plain rectangle
    // crop, an approximation (the quad's own axis-aligned bounding box) for a Free corners quad,
    // since those overlays aren't themselves warped through the same projective transform a
    // Perspective warp (or the old Free-corners-as-Perspective mode) applies.
    val effectiveCropRect = if (state.cropMode == CropMode.FREE_CORNERS) {
        boundingRectOf(state.cropQuad ?: CropQuad.fromRect(state.cropRect))
    } else {
        state.cropRect
    }

    val output = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    val paint = Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(buildColorMatrix(state)) }
    canvas.drawBitmap(cropped, 0f, 0f, paint)

    if (state.focus.strength > 0f) {
        val cx = (state.focus.xNorm - effectiveCropRect.left) / (effectiveCropRect.right - effectiveCropRect.left) * output.width
        val cy = (state.focus.yNorm - effectiveCropRect.top) / (effectiveCropRect.bottom - effectiveCropRect.top) * output.height
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

    for (sticker in state.stickers) {
        val relX = (sticker.xNorm - effectiveCropRect.left) / (effectiveCropRect.right - effectiveCropRect.left)
        val relY = (sticker.yNorm - effectiveCropRect.top) / (effectiveCropRect.bottom - effectiveCropRect.top)
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            // output.width / 18f at the sticker's own default 22sp/1x preview size matches the old
            // fixed-size behavior; scaling from there keeps the typed text size and sticker size
            // sliders' effect proportionally consistent with what the live preview showed.
            textSize = (output.width / 18f) * (sticker.textSizeSp / 22f) * sticker.scale
            color = sticker.colorArgb.toInt()
        }
        val x = relX * output.width
        val y = relY * output.height
        if (sticker.backgroundArgb != null) {
            val bounds = Rect()
            textPaint.getTextBounds(sticker.text, 0, sticker.text.length, bounds)
            val pad = textPaint.textSize * 0.2f
            val bgPaint = Paint().apply { color = sticker.backgroundArgb.toInt() }
            canvas.drawRect(
                x + bounds.left - pad, y + bounds.top - pad,
                x + bounds.right + pad, y + bounds.bottom + pad,
                bgPaint,
            )
        }
        canvas.drawText(sticker.text, x, y, textPaint)
    }

    // Straighten is applied inside applyCrop, BEFORE cropping (see its own doc comment) -- the
    // crop rect/quad the user drew was always relative to the already-straightened, canvas-
    // expanded frame shown in the live preview, not the original un-rotated bitmap, so there's no
    // separate rotation step needed here on top of the already-cropped result.
    val finalBitmap = output

    // A quad crop can leave part of the output transparent (see cropQuadTransparent), and so can
    // an off-90-degree straighten (see rotateBitmapArbitrary) or a Perspective depth warp that
    // pulls a corner beyond the cropped bitmap's own edge (see applyCrop/depthAdjustedQuad) --
    // JPEG has no alpha channel at all, so saving one through it would flatten that transparency
    // to solid black. PNG (lossless, alpha-capable) is used instead whenever any of these apply,
    // OR when a PRIOR Done bake already left real transparency in the incoming bitmap itself
    // (hasBakedAlpha -- see PhotoEditScreen's bakeGeometry) even though the CURRENTLY pending
    // state above is fully opaque. A plain rectangle crop with no straighten or perspective and no
    // earlier bake is always fully opaque, so JPEG still applies there as before.
    val hasTransparency = hasBakedAlpha ||
        state.cropMode == CropMode.FREE_CORNERS ||
        !state.perspectiveDepths.isIdentity ||
        state.straightenDegrees % 90f != 0f
    val resolver = context.contentResolver
    val baseName = original.displayName.substringBeforeLast('.', original.displayName)
    val extension = if (hasTransparency) "png" else "jpg"
    val fileName = if (replace) "$baseName.$extension" else "${baseName}_edited_${System.currentTimeMillis() / 1000}.$extension"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, if (hasTransparency) "image/png" else "image/jpeg")
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
        resolver.openOutputStream(uri)?.use { out ->
            finalBitmap.compress(if (hasTransparency) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, out)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val doneValues = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, doneValues, null, null)
        }
    }
}
