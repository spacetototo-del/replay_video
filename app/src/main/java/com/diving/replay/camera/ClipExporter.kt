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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        val snapshot = rotation.snapshot()
        val plan = ClipCutPlanner.plan(snapshot, startMs, endMs)
        if (plan == null) {
            DiagnosticLog.logFailure(
                context, "nothing to save",
                mapOf(
                    "requestedStartMs" to startMs,
                    "requestedEndMs" to endMs,
                    "requestedSpanMs" to (endMs - startMs),
                    "bufferSegments" to snapshot.size,
                    "bufferSpanMs" to snapshot.takeIf { it.isNotEmpty() }
                        ?.let { it.last().endedAtMs - it.first().startedAtMs },
                ),
            )
            return Result.NothingToSave
        }

        // Transformer occasionally throws ExoTimeoutException("Player release timed out") when a
        // multi-segment composition switches between per-segment asset loaders — real failures
        // seen in the field (3-4 segments, plenty of heap free, so not an OOM) that kept
        // recurring even one retry later. A longer gap between attempts gives whatever hardware
        // decoder slot it's contending for more of a chance to actually free up.
        var lastError: Throwable? = null
        repeat(EXPORT_ATTEMPTS) { attempt ->
            val (saved, error) = buildAndRun(plan, partial, speed)
            if (saved != null) return saved
            lastError = error
            if (attempt < EXPORT_ATTEMPTS - 1) {
                Log.w(TAG, "export attempt ${attempt + 1}/$EXPORT_ATTEMPTS failed, retrying", error)
                delay(800)
            }
        }
        // slow export failed — fall back to normal speed
        if (speed != 1f) {
            Log.w(TAG, "slow export ($speed x) failed after retries, falling back to 1x")
            val (saved, error) = buildAndRun(plan, partial, 1f)
            if (saved != null) return saved
            lastError = error
        }
        logExportFailure(plan, speed, lastError)
        return Result.Failed("transform error")
    }

    private fun logExportFailure(plan: ClipPlan, speed: Float, error: Throwable?) {
        val rt = Runtime.getRuntime()
        DiagnosticLog.logFailure(
            context, "export failed",
            mapOf(
                "requestedSpeed" to speed,
                "effectiveStartMs" to plan.effectiveStartMs,
                "effectiveEndMs" to plan.effectiveEndMs,
                "requestedSpanMs" to (plan.effectiveEndMs - plan.effectiveStartMs),
                "keptDurationMs" to plan.keptDurationMs,
                "segmentCount" to plan.cuts.size,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}",
                "heapUsedMB" to (rt.totalMemory() - rt.freeMemory()) / 1_000_000,
                "heapMaxMB" to rt.maxMemory() / 1_000_000,
                "freeDiskMB" to runCatching { context.filesDir.usableSpace / 1_000_000 }.getOrNull(),
            ),
            error = error,
        )
    }

    /** Null [Result.Saved] paired with the causing error on transform failure, so the caller can
     *  retry / fall back / log without redoing the try-catch. */
    private suspend fun buildAndRun(plan: ClipPlan, partial: Boolean, speed: Float): Pair<Result.Saved?, Throwable?> {
        val editedItems = plan.cuts.map { cut ->
            val mediaBuilder = MediaItem.Builder().setUri(cut.segment.file.toURI().toString())
            if (cut.startInSegmentMs > 0 || cut.endInSegmentMs != ClipCut.KEEP_TO_END) {
                mediaBuilder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(cut.startInSegmentMs)
                        .apply {
                            if (cut.endInSegmentMs != ClipCut.KEEP_TO_END) {
                                setEndPositionMs(cut.endInSegmentMs)
                            }
                        }
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
                else (plan.keptDurationMs / speed).toLong()
            val (uri, name) = withContext(Dispatchers.IO) {
                publishToGallery(cacheOut, plan.effectiveStartMs)
            }
            cacheOut.delete()
            Result.Saved(uri, name, savedDurationMs, partial, speed) to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: a 30-minute buffer is ~120 segments for Transformer to
            // decode+re-encode in one pass, and that's exactly the shape of thing that can throw
            // OutOfMemoryError rather than a plain Exception. Catching it here turns a silent
            // service crash into a clean Failed result plus a diagnostic entry.
            Log.e(TAG, "export failed (speed=$speed)", e)
            cacheOut.delete()
            null to e
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

        /** Attempts at the requested speed before falling back to 1x (if slow) / giving up. */
        private const val EXPORT_ATTEMPTS = 3
    }
}
