package com.diving.replay.camera

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Concatenates already-finished mp4 files end to end into one file, by copying compressed
 * samples straight from [MediaExtractor] to [MediaMuxer] — no decode, no re-encode, and
 * critically, no [androidx.media3.transformer.Transformer]. That matters because Transformer's
 * own internal player hand-off between sequence items is what throws
 * `ExoTimeoutException: Player release timed out` (see [ClipExporter]); this path never creates
 * that per-item player at all, so a long clip (many pieces) isn't more likely to fail than a
 * short one.
 *
 * All [inputs] must share the same video (and, if present, audio) format — [ClipExporter] only
 * ever feeds this pieces that already came out of the same [Transformer]-with-default-settings
 * pass, so that holds by construction. This is not a general-purpose muxer.
 */
object RawMp4Concatenator {

    fun concatenate(inputs: List<File>, outputPath: String) {
        require(inputs.isNotEmpty()) { "no inputs to concatenate" }
        if (inputs.size == 1) {
            inputs[0].copyTo(File(outputPath), overwrite = true)
            return
        }

        // Whether every piece actually has an audio track — if even one doesn't (mic permission
        // toggled mid-buffer, or a Transformer fragment dropped it for some other reason), skip
        // audio entirely rather than crash trying to mux a track a later piece can't supply.
        val hasAudio = inputs.all { trackIndexOf(it, "audio/") >= 0 }

        val firstExtractor = MediaExtractor().apply { setDataSource(inputs.first().absolutePath) }
        val videoFormat = firstExtractor.trackFormatOrNull("video/")
            ?: error("no video track in ${inputs.first()}")
        val audioFormat = if (hasAudio) firstExtractor.trackFormatOrNull("audio/") else null
        firstExtractor.release()

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val rotation = runCatching { videoFormat.getInteger(MediaFormat.KEY_ROTATION) }.getOrDefault(0)
        if (rotation != 0) muxer.setOrientationHint(rotation)

        val outVideoTrack = muxer.addTrack(videoFormat)
        val outAudioTrack = audioFormat?.let { muxer.addTrack(it) }
        muxer.start()

        var videoOffsetUs = 0L
        var audioOffsetUs = 0L
        try {
            for (input in inputs) {
                val extractor = MediaExtractor()
                extractor.setDataSource(input.absolutePath)

                val videoIn = trackIndexOf(extractor, "video/")
                if (videoIn >= 0) {
                    videoOffsetUs += copyTrack(extractor, videoIn, muxer, outVideoTrack, videoOffsetUs)
                }
                if (outAudioTrack != null) {
                    val audioIn = trackIndexOf(extractor, "audio/")
                    if (audioIn >= 0) {
                        audioOffsetUs += copyTrack(extractor, audioIn, muxer, outAudioTrack, audioOffsetUs)
                    }
                }
                extractor.release()
            }
        } finally {
            runCatching { muxer.stop() }
            muxer.release()
        }
    }

    /** Copies every sample of [trackIn] into [outTrack], shifted so it starts at [baseOffsetUs].
     *  Returns this piece's own duration (last sample time + its share of decode time), i.e. how
     *  far the *next* piece's offset should advance — tracked per-track so a video/audio duration
     *  mismatch within one file doesn't accumulate drift across many pieces. */
    private fun copyTrack(
        extractor: MediaExtractor,
        trackIn: Int,
        muxer: MediaMuxer,
        outTrack: Int,
        baseOffsetUs: Long,
    ): Long {
        extractor.selectTrack(trackIn)
        val format = extractor.getTrackFormat(trackIn)
        val maxInputSize = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
            .getOrDefault(DEFAULT_BUFFER_SIZE)
            .coerceAtLeast(DEFAULT_BUFFER_SIZE)
        val buffer = ByteBuffer.allocate(maxInputSize)
        val bufferInfo = MediaCodec.BufferInfo()

        var firstSampleTimeUs = -1L
        var lastSampleTimeUs = 0L
        var sampleCount = 0
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val sampleTimeUs = extractor.sampleTime
            if (firstSampleTimeUs < 0) firstSampleTimeUs = sampleTimeUs
            lastSampleTimeUs = sampleTimeUs
            sampleCount++
            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else 0
            bufferInfo.set(0, size, (sampleTimeUs - firstSampleTimeUs) + baseOffsetUs, flags)
            muxer.writeSampleData(outTrack, buffer, bufferInfo)
            extractor.advance()
        }
        extractor.unselectTrack(trackIn)
        val spanUs = (lastSampleTimeUs - firstSampleTimeUs).coerceAtLeast(0L)
        // The span between the first and *last* sample undercounts this piece's true duration by
        // exactly one sample interval (nothing follows the last sample to mark where it ends) —
        // return that short, and the next piece's first sample lands on the exact same output
        // timestamp as this piece's last one instead of after it. Muxers reject that as a
        // non-increasing timestamp (surfaced as stray splice-point glitches, one per piece
        // boundary, on playback). Extrapolate one more average sample interval to cover it.
        val avgIntervalUs = if (sampleCount > 1) spanUs / (sampleCount - 1) else 0L
        return spanUs + avgIntervalUs
    }

    private fun trackIndexOf(file: File, mimePrefix: String): Int {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            trackIndexOf(extractor, mimePrefix)
        } finally {
            extractor.release()
        }
    }

    private fun trackIndexOf(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun MediaExtractor.trackFormatOrNull(mimePrefix: String): MediaFormat? {
        val index = trackIndexOf(this, mimePrefix)
        return if (index >= 0) getTrackFormat(index) else null
    }

    // Comfortably covers a 4K keyframe (the app's top resolution option); smaller tracks just
    // waste a bit of heap on one reused buffer, not a real cost.
    private const val DEFAULT_BUFFER_SIZE = 4 * 1024 * 1024
}
