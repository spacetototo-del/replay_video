package com.diving.replay.camera

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.diving.replay.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Turns the [startMs, endMs] marker window into one physical mp4 and drops it in the gallery
 * (plan §2 ClipExporter, §4 Phase 3).
 *
 * Strategy: build an [EditedMediaItemSequence] of the overlapping segments, clipping the first
 * and last so the seams land on the markers, run [Transformer] into a cache file, then publish
 * that file into MediaStore under Movies/DivingReplay.
 *
 * If the requested start is older than what the ring still holds, we export whatever survives
 * and report [Result.Saved.partial] = true so the watch can say "일부만 저장됨" (plan §4 Phase 3).
 */
@UnstableApi
class ClipExporter(
    private val context: Context,
    private val rotation: SegmentRotationManager,
) {
    sealed interface Result {
        data class Saved(
            val uriString: String,
            val displayName: String,
            val savedDurationMs: Long,
            val partial: Boolean,
            /** Speed actually baked into the file. May be 1f even when slower was requested,
             *  if the slow-motion export path failed and we fell back. */
            val speed: Float = 1f,
        ) : Result

        data class Failed(val reason: String) : Result
        data object NothingToSave : Result
    }

    /**
     * Transformer must be built + started on a Looper thread; we hop to Main for that.
     * [speed] < 1 bakes slow motion into the file (audio is dropped for slow clips). If the
     * slow path throws, we retry once at 1x so the moment is still captured.
     */
    suspend fun export(startMs: Long, endMs: Long, speed: Float = 1f): Result {
        val partial = rotation.isStartTruncated(startMs)
        val segments = rotation.windowBetween(startMs, endMs)
        if (segments.isEmpty()) return Result.NothingToSave

        val effectiveStart = maxOf(startMs, segments.first().startedAtMs)
        val effectiveEnd = minOf(endMs, segments.last().endedAtMs)
        if (effectiveEnd - effectiveStart < MIN_CLIP_MS) return Result.NothingToSave

        buildAndRun(segments, effectiveStart, effectiveEnd, partial, speed)?.let { return it }
        // slow export failed — fall back to normal speed
        return if (speed != 1f) {
            Log.w(TAG, "slow export ($speed x) failed, retrying at 1x")
            buildAndRun(segments, effectiveStart, effectiveEnd, partial, 1f)
                ?: Result.Failed("transform error")
        } else {
            Result.Failed("transform error")
        }
    }

    /** Returns null on transform failure so the caller can retry / fall back. */
    private suspend fun buildAndRun(
        segments: List<Segment>,
        effectiveStart: Long,
        effectiveEnd: Long,
        partial: Boolean,
        speed: Float,
    ): Result? {
        val editedItems = segments.mapIndexed { index, seg ->
            val isFirst = index == 0
            val isLast = index == segments.lastIndex
            val startInSegMs = if (isFirst) (effectiveStart - seg.startedAtMs).coerceAtLeast(0) else 0L
            val endInSegMs = if (isLast) {
                (effectiveEnd - seg.startedAtMs).coerceIn(1L, seg.durationMs.coerceAtLeast(1L))
            } else {
                -1L
            }
            val mediaBuilder = MediaItem.Builder().setUri(seg.file.toURI().toString())
            if (startInSegMs > 0 || endInSegMs >= 0) {
                mediaBuilder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startInSegMs)
                        .apply { if (endInSegMs >= 0) setEndPositionMs(endInSegMs) }
                        .build(),
                )
            }
            EditedMediaItem.Builder(mediaBuilder.build())
                .apply {
                    if (speed != 1f) {
                        setEffects(Effects(emptyList(), listOf(SpeedChangeEffect(speed))))
                        setRemoveAudio(true) // slow-motion clips are silent
                    }
                }
                .build()
        }

        val composition = Composition.Builder(EditedMediaItemSequence(editedItems)).build()
        val cacheOut = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")

        return try {
            val result = withContext(Dispatchers.Main) { runTransformer(composition, cacheOut) }
            val savedDurationMs =
                if (result.durationMs > 0) result.durationMs
                else ((effectiveEnd - effectiveStart) / speed).toLong()
            val (uri, name) = withContext(Dispatchers.IO) { publishToGallery(cacheOut, effectiveStart) }
            cacheOut.delete()
            Result.Saved(uri, name, savedDurationMs, partial, speed)
        } catch (e: Exception) {
            Log.e(TAG, "export failed (speed=$speed)", e)
            cacheOut.delete()
            null
        }
    }

    private suspend fun runTransformer(composition: Composition, out: File): ExportResult =
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(c: Composition, result: ExportResult) {
                        if (cont.isActive) cont.resume(result)
                    }

                    override fun onError(c: Composition, result: ExportResult, ex: ExportException) {
                        if (cont.isActive) cont.resumeWithException(ex)
                    }
                })
                .build()
            transformer.start(composition, out.absolutePath)
            cont.invokeOnCancellation { runCatching { transformer.cancel() } }
        }

    private fun publishToGallery(source: File, startedAtMs: Long): Pair<String, String> {
        val name = "diving_${startedAtMs}.mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Constants.EXPORT_RELATIVE_DIR)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert returned null")
        resolver.openOutputStream(uri).use { os ->
            source.inputStream().use { input -> input.copyTo(os!!) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri.toString() to name
    }

    companion object {
        private const val TAG = "ClipExporter"
        private const val MIN_CLIP_MS = 300L
    }
}
