package com.elghayesh.gallerybackup.ui.edit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Effect
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.Crop
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The actual Media3 Transformer/MediaStore work behind trimming, removing a selection, changing
 * speed/audio, and merging -- shared between [VideoTrimScreen]/[VideoMergeScreen] (running it
 * live while the editor screen is open) and [VideoExportWorker] (running the identical code as a
 * foreground-service-backed background job, so a long export survives the app being minimized).
 * Kept in one place specifically so there's exactly one copy of this logic to get right.
 */

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
 * A [speed] other than 1x, a non-zero [rotationDegrees], or a [cropRect] narrower than the full
 * frame all force a full re-encode too, for the same underlying reason: each rewrites every
 * frame's timestamp or pixels ([SpeedChangeEffect]/[ScaleAndRotateTransformation]/[Crop]), which is
 * exactly what the near-lossless trim path above depends on NOT happening to any frame it stream-
 * copies. [muteAudio] drops the audio track entirely rather than just silencing it (silence would
 * still be decoded, time-stretched and re-encoded for nothing); otherwise the audio is time-
 * stretched by the same factor as the video via [SonicAudioProcessor] so picture and sound stay in
 * sync, keeping its original pitch rather than the chipmunk/slow-motion-voice effect a plain
 * resample would give.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun transformVideo(
    context: Context,
    sourceUri: Uri,
    startMs: Long,
    endMs: Long,
    totalDurationMs: Long,
    removeSelection: Boolean,
    speed: Float,
    muteAudio: Boolean,
    /** Clockwise, one of 0/90/180/270. */
    rotationDegrees: Int,
    /** Relative to the ALREADY-rotated frame (i.e. what [rotationDegrees] produces) -- null or
     * [NormRect.FULL] means no crop. */
    cropRect: NormRect?,
    outputPath: String,
    onProgress: (Int) -> Unit,
): Boolean = coroutineScope {
    val hasCrop = cropRect != null && cropRect != NormRect.FULL
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
                    .setEffects(videoEditEffects(speed, muteAudio, rotationDegrees, cropRect))
                    .build()

            if (!removeSelection) {
                val editedMediaItem = buildPiece(startMs, endMs)
                val transformerBuilder = Transformer.Builder(context)
                    .setMuxerFactory(originalTimestampPreservingMuxerFactory())
                    .addListener(listener)
                // Trim optimization stream-copies every frame outside the cut, which a rotate/crop
                // effect can't do -- it needs to actually touch every frame's pixels, same as a
                // non-1x speed already ruled this path out for.
                if (speed == 1f && rotationDegrees == 0 && !hasCrop) {
                    transformerBuilder.experimentalSetTrimOptimizationEnabled(true)
                }
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
                val transformer = Transformer.Builder(context)
                    .setMuxerFactory(originalTimestampPreservingMuxerFactory())
                    .addListener(listener)
                    .build()
                transformerRef = transformer
                cont.invokeOnCancellation { transformer.cancel() }
                transformer.start(composition, outputPath)
            }
        }
    } finally {
        progressJob.cancel()
    }
}

/**
 * Must run on a thread with a Looper (the caller's Main dispatcher) -- Transformer requires one.
 *
 * Joins [uris], in that exact order, into a single [EditedMediaItemSequence]. Like Remove-selection
 * trimming, this is a multi-segment composition -- Transformer's trim optimization never applies
 * to one, so the whole output is re-encoded; there's no way around that when combining separate
 * source files into one.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun mergeVideos(
    context: Context,
    uris: List<Uri>,
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
            val pieces = uris.map { EditedMediaItem.Builder(ExoMediaItem.fromUri(it)).build() }
            val composition = Composition.Builder(EditedMediaItemSequence(pieces)).build()

            val transformer = Transformer.Builder(context)
                // See originalTimestampPreservingMuxerFactory's doc comment -- carries the first
                // clip's original creation time (already forwarded from the source file
                // automatically) into the merged file's own container metadata.
                .setMuxerFactory(originalTimestampPreservingMuxerFactory())
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(true, onCancellation = null)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resume(false, onCancellation = null)
                    }
                })
                .build()

            transformerRef = transformer
            cont.invokeOnCancellation { transformer.cancel() }
            transformer.start(composition, outputPath)
        }
    } finally {
        progressJob.cancel()
    }
}

/**
 * Transformer's default muxer (Android's platform [android.media.MediaMuxer], wrapped by
 * `DefaultMuxer`) always stamps the output file's own creation/modification time as "now" --
 * that's a limitation of the platform muxer itself, which has no API to set it to anything else.
 * This is true even though the original creation time IS already being read correctly: the MP4
 * extractor parses it from the source file's own `mvhd` box into an `Mp4TimestampData` entry, and
 * Transformer's `MuxerWrapper` already forwards that same entry to whichever muxer is in use --
 * the platform muxer just silently ignores it (it only acts on GPS-location entries), so the
 * value never reached the file. Media3's own `InAppMuxer` (backed by `androidx.media3.muxer.
 * Mp4Muxer`) DOES act on it, writing it into both the creation_time and modification_time fields
 * of the output's own `mvhd`/`tkhd` boxes -- so the exported file's own embedded metadata carries
 * the original date, not just the MediaStore row we separately set below. Supports H.264/H.265/
 * AV1 video and AAC audio, which covers ordinary phone-recorded video.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun originalTimestampPreservingMuxerFactory() = InAppMuxer.Factory.Builder().build()

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
 * Rotation is applied BEFORE crop -- [cropRect] is defined relative to the already-rotated frame
 * (what the Crop tab's overlay was drawn on top of in the live preview), and Media3 applies a
 * [Effects.videoEffects] list in sequence, so putting rotation first in that list is what makes
 * the two line up. [SpeedChangeEffect] re-times the video frames; [SonicAudioProcessor.setSpeed]
 * re-times the audio by the same factor (its pitch stays at the class default of 1x, so speeding
 * up or slowing down doesn't chipmunk or drawl the audio) so picture and sound stay in sync. Each
 * piece is skipped entirely when it wouldn't do anything (a no-op that would otherwise still force
 * a full re-encode), and the audio speed change is skipped whenever [muteAudio] drops the audio
 * track anyway.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun videoEditEffects(speed: Float, muteAudio: Boolean, rotationDegrees: Int, cropRect: NormRect?): Effects {
    val videoEffects = buildList<Effect> {
        if (rotationDegrees != 0) {
            // ScaleAndRotateTransformation rotates counterclockwise; rotationDegrees here is the
            // user-facing clockwise angle (matching the live preview's graphicsLayer.rotationZ,
            // also clockwise-positive), so it's inverted going into the export effect.
            add(
                ScaleAndRotateTransformation.Builder()
                    .setRotationDegrees((360 - rotationDegrees).toFloat() % 360f)
                    .build(),
            )
        }
        if (cropRect != null && cropRect != NormRect.FULL) add(cropEffectFor(cropRect))
        if (speed != 1f) add(SpeedChangeEffect(speed))
    }
    val audioProcessors = if (speed != 1f && !muteAudio) {
        listOf(SonicAudioProcessor().apply { setSpeed(speed) })
    } else {
        emptyList()
    }
    return Effects(audioProcessors, videoEffects)
}

/**
 * [NormRect] is normalized 0..1 with y increasing downward (top < bottom, matching how
 * [CropOverlay] draws it); [Crop] takes normalized device coordinates (-1..1, y increasing
 * upward), so both axes need converting -- x is a straight rescale, y also flips direction.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun cropEffectFor(rect: NormRect): Crop {
    val leftNdc = rect.left * 2f - 1f
    val rightNdc = rect.right * 2f - 1f
    val topNdc = 1f - rect.top * 2f
    val bottomNdc = 1f - rect.bottom * 2f
    return Crop(leftNdc, rightNdc, bottomNdc, topNdc)
}

/**
 * Inserts the exported file at [outputPath] into MediaStore, named and dated like an edit of
 * [displayName] (the original's) rather than an anonymous new file -- [replace] controls only the
 * filename (reuse the original's base name vs. append a suffix); trashing the actual original row,
 * where that needs to happen, is the caller's job (it needs a live Activity for the system consent
 * dialog on Android 11+, which this function -- callable from a background worker -- can't assume).
 */
internal suspend fun saveTrimmedVideo(
    context: Context,
    outputPath: String,
    displayName: String,
    folderPath: String,
    dateTakenSec: Long,
    dateModifiedSec: Long,
    replace: Boolean,
) = withContext(Dispatchers.IO) {
    val tempFile = File(outputPath)
    if (!tempFile.exists()) return@withContext

    val baseName = displayName.substringBeforeLast('.', displayName)
    val fileName = if (replace) "$baseName.mp4" else "${baseName}_trimmed_${System.currentTimeMillis() / 1000}.mp4"
    saveVideoToMediaStore(context, tempFile, fileName, folderPath, dateTakenSec, dateModifiedSec)
}

/** See [saveTrimmedVideo] -- same insert, but always named/suffixed like a new file since a merge
 * never replaces any of its source videos. */
internal suspend fun saveMergedVideo(
    context: Context,
    outputPath: String,
    displayName: String,
    folderPath: String,
    dateTakenSec: Long,
    dateModifiedSec: Long,
) = withContext(Dispatchers.IO) {
    val tempFile = File(outputPath)
    if (!tempFile.exists()) return@withContext

    val baseName = displayName.substringBeforeLast('.', displayName)
    val fileName = "${baseName}_merged_${System.currentTimeMillis() / 1000}.mp4"
    saveVideoToMediaStore(context, tempFile, fileName, folderPath, dateTakenSec, dateModifiedSec)
}

private fun saveVideoToMediaStore(
    context: Context,
    tempFile: File,
    fileName: String,
    folderPath: String,
    dateTakenSec: Long,
    dateModifiedSec: Long,
) {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        // Belt-and-suspenders alongside the muxer-level fix above: without these, a freshly-
        // inserted MediaStore row defaults its dates to "now" even before any rescan.
        put(MediaStore.Video.Media.DATE_TAKEN, dateTakenSec * 1000)
        put(MediaStore.Video.Media.DATE_MODIFIED, dateModifiedSec)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, folderPath.ifEmpty { "Movies" })
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

/**
 * Grabs the exact frame at [atMs] from [sourceUri] (a video) and saves it as a new JPEG in the
 * same folder as the video, named "<video's base name> Screenshot <n>.jpg" -- n is the first
 * number not already used by an earlier screenshot saved from this same video, so saving several
 * frames in one session doesn't overwrite the previous ones. Carries over the video's own date
 * onto both the MediaStore row and the new JPEG's own EXIF DateTimeOriginal/DateTime tags -- a
 * video has no literal EXIF segment the way a JPEG does, so there's nothing to copy the way a
 * photo edit copies its original's EXIF bytes; the date is the part of "same exif data" that
 * actually carries over.
 *
 * OPTION_CLOSEST decodes forward to the exact requested frame rather than snapping to the nearest
 * keyframe (OPTION_CLOSEST_SYNC) -- slower, but this is a one-off save of a frame the user
 * specifically chose, not a scrub preview, so landing on precisely that frame matters more than
 * speed here. Falls back to OPTION_CLOSEST_SYNC on API 26 (this app's minSdk), one version below
 * where OPTION_CLOSEST was added.
 *
 * Returns the saved file's display name, or null if the frame couldn't be decoded or the insert
 * failed.
 */
internal suspend fun saveFrameAsPhoto(
    context: Context,
    sourceUri: Uri,
    atMs: Long,
    displayName: String,
    folderPath: String,
    dateTakenSec: Long,
    dateModifiedSec: Long,
): String? = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    var sourceLatLong: DoubleArray? = null
    val bitmap = try {
        retriever.setDataSource(context, sourceUri)
        // METADATA_KEY_LOCATION reads the video container's own location atom (ISO 6709 text,
        // e.g. "+37.5090-122.2660/") when the source was recorded with location tagging on --
        // the only "EXIF data" a video actually carries that a screenshot can meaningfully
        // inherit beyond its date, which was already being copied.
        sourceLatLong = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            ?.let { parseIsoLocation(it) }
        val option = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            MediaMetadataRetriever.OPTION_CLOSEST
        } else {
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        }
        retriever.getFrameAtTime(atMs * 1000, option)
    } catch (e: Exception) {
        null
    } finally {
        retriever.release()
    }
    if (bitmap == null) return@withContext null

    val baseName = displayName.substringBeforeLast('.', displayName)
    val fileName = nextScreenshotFileName(context, baseName)
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.DATE_TAKEN, dateTakenSec * 1000)
        put(MediaStore.Images.Media.DATE_MODIFIED, dateModifiedSec)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, folderPath.ifEmpty { "Pictures" })
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (uri == null) {
        bitmap.recycle()
        return@withContext null
    }
    resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out) }
    bitmap.recycle()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }
    try {
        resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            val dateStr = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(dateTakenSec * 1000))
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)
            sourceLatLong?.let { exif.setLatLong(it[0], it[1]) }
            exif.saveAttributes()
        }
    } catch (e: Exception) {
        // Best-effort -- the file and its MediaStore date are already correct either way.
    }
    fileName
}

/** Parses an ISO 6709 location string (what [MediaMetadataRetriever.METADATA_KEY_LOCATION]
 * returns, e.g. "+37.5090-122.2660/" or "+37.5090-122.2660+010.000/" with altitude) into
 * [latitude, longitude], or null if it doesn't match that format. */
private fun parseIsoLocation(location: String): DoubleArray? {
    val match = Regex("^([+-][0-9.]+)([+-][0-9.]+)").find(location.trim()) ?: return null
    val lat = match.groupValues[1].toDoubleOrNull() ?: return null
    val lon = match.groupValues[2].toDoubleOrNull() ?: return null
    return doubleArrayOf(lat, lon)
}

private fun nextScreenshotFileName(context: Context, baseName: String): String {
    val existingNumbers = mutableSetOf<Int>()
    val pattern = Regex("^${Regex.escape(baseName)} Screenshot (\\d+)\\.jpg$", RegexOption.IGNORE_CASE)
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
        "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
        arrayOf("$baseName Screenshot %"),
        null,
    )?.use { cursor ->
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        while (cursor.moveToNext()) {
            pattern.find(cursor.getString(nameCol))?.groupValues?.get(1)?.toIntOrNull()?.let { existingNumbers.add(it) }
        }
    }
    var n = 1
    while (n in existingNumbers) n++
    return "$baseName Screenshot $n.jpg"
}
