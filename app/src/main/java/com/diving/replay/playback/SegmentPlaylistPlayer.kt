package com.diving.replay.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.diving.replay.camera.Segment

/**
 * Plays the buffer segments back-to-back as one virtual timeline for the rewind scrubber
 * (plan §1.1, §4 Phase 2). No files are merged — ExoPlayer just plays a playlist, and
 * [SegmentTimeline] maps a scrub position onto (mediaItemIndex, positionMs) and back onto
 * wall-clock time for the exporter.
 */
@UnstableApi
class SegmentPlaylistPlayer(context: Context) {

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        // Frame-accurate seeks so the frame-step buttons land on the exact frame.
        setSeekParameters(SeekParameters.EXACT)
    }

    var timeline: SegmentTimeline = SegmentTimeline(emptyList())
        private set

    /** Total scrubbable duration in ms. */
    val totalDurationMs: Long get() = timeline.totalDurationMs

    fun load(segments: List<Segment>) {
        timeline = SegmentTimeline(segments)
        exoPlayer.setMediaItems(timeline.segments.map(::mediaItemFor))
        exoPlayer.prepare()
    }

    /**
     * Bring the playlist in line with a still-growing buffer *without* interrupting playback —
     * what continuous delayed replay needs, since [load] would restart it every 15 seconds.
     *
     * The ring only ever loses items from the front and gains them at the back, so this is an
     * append plus a front-trim; ExoPlayer keeps playing the current item through both. Anything
     * that doesn't match that shape (a resolution change wipes the buffer, for instance) falls
     * back to a full reload.
     */
    fun sync(segments: List<Segment>) {
        val next = SegmentTimeline(segments)
        val oldFiles = timeline.segments.map { it.file }
        val newFiles = next.segments.map { it.file }

        if (oldFiles == newFiles) {
            timeline = next // same files; durations may have been filled in
            return
        }
        if (oldFiles.isEmpty() || newFiles.isEmpty()) {
            load(segments)
            return
        }

        val dropped = oldFiles.indexOf(newFiles.first()).let { if (it < 0) oldFiles.size else it }
        val kept = oldFiles.drop(dropped)
        if (kept != newFiles.take(kept.size)) {
            load(segments) // not a simple slide — rebuild
            return
        }

        if (dropped > 0) exoPlayer.removeMediaItems(0, dropped)
        val appended = next.segments.drop(kept.size)
        if (appended.isNotEmpty()) exoPlayer.addMediaItems(appended.map(::mediaItemFor))
        timeline = next
    }

    private fun mediaItemFor(segment: Segment): MediaItem =
        MediaItem.fromUri(segment.file.toURI().toString())

    /** Seek to [positionMs] measured from the start of the virtual timeline. */
    fun seekToTimeline(positionMs: Long) {
        if (timeline.isEmpty) return
        val (index, within) = timeline.positionInItem(positionMs)
        exoPlayer.seekTo(index, within)
    }

    /** Playhead position on the virtual timeline, in ms. */
    fun currentTimelineMs(): Long {
        if (timeline.isEmpty) return 0
        val index = exoPlayer.currentMediaItemIndex.coerceIn(0, timeline.segments.lastIndex)
        return (timeline.offsetOf(index) + exoPlayer.currentPosition)
            .coerceIn(0, timeline.totalDurationMs)
    }

    /**
     * Wall-clock time for a 0..1 position on the scrubber. This is what the exporter cuts
     * against, so it goes through the timeline's real per-segment timestamps rather than
     * assuming the segments are contiguous.
     */
    fun wallClockAtFraction(fraction: Float): Long = timeline.fractionToWallClock(fraction)

    /** Where the playhead sits as a 0..1 fraction of the virtual timeline. */
    fun timelineFraction(): Float {
        val total = timeline.totalDurationMs
        if (total <= 0) return 0f
        return (currentTimelineMs().toFloat() / total).coerceIn(0f, 1f)
    }

    val isPlaying: Boolean get() = exoPlayer.isPlaying
    fun atEnd(): Boolean = exoPlayer.playbackState == Player.STATE_ENDED

    fun setSpeed(speed: Float) { exoPlayer.setPlaybackSpeed(speed) }

    fun play() { exoPlayer.play() }
    fun pause() { exoPlayer.pause() }

    fun release() {
        exoPlayer.release()
    }
}
