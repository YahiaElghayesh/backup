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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.elghayesh.gallerybackup.data.media.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class CropAspect(val label: String, val ratio: Float) {
    FREE("Free", 0f),
    SQUARE("1:1", 1f),
    FOUR_THREE("4:3", 4f / 3f),
    SIXTEEN_NINE("16:9", 16f / 9f),
}

private enum class PhotoFilter(val label: String) {
    NONE("None"),
    GRAYSCALE("Grayscale"),
    SEPIA("Sepia"),
    COOL("Cool"),
    WARM("Warm"),
    INVERT("Invert"),
}

private enum class EditTool { CROP, ADJUST, FILTERS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoEditScreen(
    item: MediaItem,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var workingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }

    var tool by remember { mutableStateOf(EditTool.CROP) }
    var cropAspect by remember { mutableStateOf(CropAspect.FREE) }
    var cropCenter by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(0f) }
    var filter by remember { mutableStateOf(PhotoFilter.NONE) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(item.uri) {
        isLoading = true
        workingBitmap = loadDownsampledBitmap(context, item.uri, maxDimension = 2048)
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit photo") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(
                        enabled = workingBitmap != null,
                        onClick = { workingBitmap = workingBitmap?.let { rotateBitmap90(it) } },
                    ) {
                        Icon(Icons.Filled.RotateRight, contentDescription = "Rotate")
                    }
                    TextButton(
                        enabled = workingBitmap != null && !isSaving,
                        onClick = {
                            val bitmap = workingBitmap ?: return@TextButton
                            val cropRect = cropRectFor(bitmap.width, bitmap.height, cropAspect, cropCenter)
                            val matrix = buildColorMatrix(brightness, contrast, saturation, filter)
                            isSaving = true
                            scope.launch {
                                saveEditedPhoto(context, bitmap, cropRect, matrix, item)
                                isSaving = false
                                onDone()
                            }
                        },
                    ) {
                        Text("Save")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val bitmap = workingBitmap
                when {
                    isLoading -> CircularProgressIndicator()
                    bitmap == null -> Text("Couldn't load this photo.")
                    else -> {
                        val composeMatrix = buildColorMatrix(brightness, contrast, saturation, filter)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                                .onSizeChanged { boxSize = it }
                                .pointerInput(cropAspect, bitmap) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        if (cropAspect != CropAspect.FREE && boxSize.width > 0 && boxSize.height > 0) {
                                            cropCenter = Offset(
                                                (cropCenter.x + dragAmount.x / boxSize.width).coerceIn(0f, 1f),
                                                (cropCenter.y + dragAmount.y / boxSize.height).coerceIn(0f, 1f),
                                            )
                                        }
                                    }
                                },
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = item.displayName,
                                contentScale = ContentScale.Fit,
                                colorFilter = ColorFilter.colorMatrix(
                                    androidx.compose.ui.graphics.ColorMatrix(composeMatrix.array),
                                ),
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (cropAspect != CropAspect.FREE) {
                                CropOverlay(bitmap.width, bitmap.height, cropAspect, cropCenter)
                            }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditTool.entries.forEach { t ->
                    FilterChip(selected = tool == t, onClick = { tool = t }, label = { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
            }

            when (tool) {
                EditTool.CROP -> Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CropAspect.entries.forEach { a ->
                        FilterChip(selected = cropAspect == a, onClick = { cropAspect = a; cropCenter = Offset(0.5f, 0.5f) }, label = { Text(a.label) })
                    }
                }
                EditTool.ADJUST -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LabeledSlider("Brightness", brightness) { brightness = it }
                    LabeledSlider("Contrast", contrast) { contrast = it }
                    LabeledSlider("Saturation", saturation) { saturation = it }
                    Spacer(Modifier.height(8.dp))
                }
                EditTool.FILTERS -> Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PhotoFilter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Text("$label: ${value.roundToInt()}", style = MaterialTheme.typography.bodySmall)
    Slider(value = value, onValueChange = onChange, valueRange = -100f..100f)
}

@Composable
private fun CropOverlay(bitmapW: Int, bitmapH: Int, aspect: CropAspect, center: Offset) {
    val rect = cropRectFor(bitmapW, bitmapH, aspect, center) ?: return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val scaleX = size.width / bitmapW
        val scaleY = size.height / bitmapH
        val left = rect.left * scaleX
        val top = rect.top * scaleY
        val right = rect.right * scaleX
        val bottom = rect.bottom * scaleY
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
    }
}

private fun cropRectFor(bitmapW: Int, bitmapH: Int, aspect: CropAspect, center: Offset): Rect? {
    if (aspect == CropAspect.FREE) return null
    var cropW = bitmapW.toFloat()
    var cropH = cropW / aspect.ratio
    if (cropH > bitmapH) {
        cropH = bitmapH.toFloat()
        cropW = cropH * aspect.ratio
    }
    val centerX = center.x * bitmapW
    val centerY = center.y * bitmapH
    val left = (centerX - cropW / 2).coerceIn(0f, bitmapW - cropW)
    val top = (centerY - cropH / 2).coerceIn(0f, bitmapH - cropH)
    return Rect(left.roundToInt(), top.roundToInt(), (left + cropW).roundToInt(), (top + cropH).roundToInt())
}

private fun buildColorMatrix(brightness: Float, contrast: Float, saturation: Float, filter: PhotoFilter): android.graphics.ColorMatrix {
    val result = android.graphics.ColorMatrix()
    result.postConcat(android.graphics.ColorMatrix().apply { setSaturation(1f + saturation / 100f) })
    val contrastScale = 1f + contrast / 100f
    val brightnessOffset = brightness / 100f * 255f
    result.postConcat(
        android.graphics.ColorMatrix(
            floatArrayOf(
                contrastScale, 0f, 0f, 0f, brightnessOffset,
                0f, contrastScale, 0f, 0f, brightnessOffset,
                0f, 0f, contrastScale, 0f, brightnessOffset,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    filterMatrixOrNull(filter)?.let { result.postConcat(it) }
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

private suspend fun saveEditedPhoto(
    context: Context,
    bitmap: Bitmap,
    cropRect: Rect?,
    colorMatrix: android.graphics.ColorMatrix,
    original: MediaItem,
) = withContext(Dispatchers.IO) {
    val cropped = if (cropRect != null) {
        Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
    } else {
        bitmap
    }
    val output = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    val paint = Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix) }
    canvas.drawBitmap(cropped, 0f, 0f, paint)

    val resolver = context.contentResolver
    val baseName = original.displayName.substringBeforeLast('.', original.displayName)
    val fileName = "${baseName}_edited_${System.currentTimeMillis() / 1000}.jpg"
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
