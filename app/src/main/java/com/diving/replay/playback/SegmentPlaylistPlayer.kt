package com.diving.replay.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.diving.replay.camera.Segment

/**
 * Plays the buffer segments back-to-back as one virtual timeline for the rewind scrubber
 * (plan §1.1, §4 Phase 2). No files are merged — ExoPlayer just plays a playlist and we map an
 * absolute "seconds into the last 3 minutes" position onto (itemIndex, positionMs).
 */
@UnstableApi
class SegmentPlaylistPlayer(context: Context) {

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    /** Segments currently loaded, oldest first, with cumulative offsets. */
    private var loaded: List<Segment> = emptyList()
    private var offsets: LongArray = LongArray(0)

    /** Total scrubbable duration in ms. */
    var totalDurationMs: Long = 0
        private set

    /** Wall-clock time the timeline starts at (start of the oldest loaded segment). */
    var timelineStartMs: Long = 0
        private set

    fun load(segments: List<Segment>) {
        loaded = segments.sortedBy { it.startedAtMs }
        offsets = LongArray(loaded.size)
        var acc = 0L
        loaded.forEachIndexed { i, seg ->
            offsets[i] = acc
            acc += seg.durationMs
        }
        totalDurationMs = acc
        timelineStartMs = loaded.firstOrNull()?.startedAtMs ?: 0

        exoPlayer.setMediaItems(loaded.map { MediaItem.fromUri(it.file.toURI().toString()) })
        exoPlayer.prepare()
    }

    /** Seek to [positionMs] measured from the start of the virtual timeline. */
    fun seekToTimeline(positionMs: Long) {
        if (loaded.isEmpty()) return
        val clamped = positionMs.coerceIn(0, totalDurationMs)
        var index = offsets.indexOfLast { it <= clamped }
        if (index < 0) index = 0
        exoPlayer.seekTo(index, clamped - offsets[index])
    }

    /** Current absolute wall-clock position the player is showing. */
    fun currentWallClockMs(): Long {
        val index = exoPlayer.currentMediaItemIndex.coerceIn(0, (loaded.size - 1).coerceAtLeast(0))
        if (loaded.isEmpty()) return 0
        return timelineStartMs + offsets[index] + exoPlayer.currentPosition
    }

    /** Where the playhead sits as a 0..1 fraction of the virtual timeline. */
    fun timelineFraction(): Float {
        if (totalDurationMs <= 0) return 0f
        val index = exoPlayer.currentMediaItemIndex.coerceIn(0, (loaded.size - 1).coerceAtLeast(0))
        val pos = offsets.getOrElse(index) { 0L } + exoPlayer.currentPosition
        return (pos.toFloat() / totalDurationMs).coerceIn(0f, 1f)
    }

    val isPlaying: Boolean get() = exoPlayer.isPlaying
    fun atEnd(): Boolean = exoPlayer.playbackState == Player.STATE_ENDED

    fun play() { exoPlayer.play() }
    fun pause() { exoPlayer.pause() }

    fun release() {
        exoPlayer.release()
    }
}
