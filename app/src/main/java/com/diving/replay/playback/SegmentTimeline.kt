package com.diving.replay.playback

import com.diving.replay.camera.Segment

/**
 * Maps between two different clocks, which is the whole subtlety of this app:
 *
 * - **wall clock** — `System.currentTimeMillis()`. What the watch markers and "save this moment"
 *   are expressed in.
 * - **timeline position** — how far into continuous playback of the buffered segments we are.
 *   This is what ExoPlayer and the scrubber slider understand.
 *
 * The two are *not* the same, because consecutive segments are not gap-free: CameraX needs a
 * moment to finalize one recording and start the next, and the very first frame lands some time
 * after `start()` is called. Treating "cumulative duration" as "elapsed wall time" silently
 * accumulates error the further back you scrub — several seconds over a 3-minute buffer — which
 * is exactly the precision this app exists to provide.
 *
 * So: playback offsets come from segment *durations*, while wall-clock conversion goes through
 * each segment's own `startedAtMs`. Gaps are skipped in playback and clamped on conversion.
 *
 * Pure logic, no Android dependencies — see `SegmentTimelineTest`.
 */
class SegmentTimeline(segments: List<Segment>) {

    /** Playable segments, oldest first. Zero-duration (unfinalized/failed) entries are dropped. */
    val segments: List<Segment> = segments.filter { it.durationMs > 0 }.sortedBy { it.startedAtMs }

    /** Cumulative playback offset of each segment, in ms. */
    private val offsets: LongArray = LongArray(this.segments.size).also { arr ->
        var acc = 0L
        this.segments.forEachIndexed { i, seg ->
            arr[i] = acc
            acc += seg.durationMs
        }
    }

    /** Total playable duration — the sum of segment durations, gaps excluded. */
    val totalDurationMs: Long = this.segments.sumOf { it.durationMs }

    val isEmpty: Boolean get() = segments.isEmpty()

    /** Wall-clock time of the first buffered frame, or 0 when empty. */
    val startWallClockMs: Long get() = segments.firstOrNull()?.startedAtMs ?: 0L

    /** Wall-clock time just past the last buffered frame, or 0 when empty. */
    val endWallClockMs: Long get() = segments.lastOrNull()?.endedAtMs ?: 0L

    /** Playback offset of [index]. */
    fun offsetOf(index: Int): Long = offsets.getOrElse(index) { 0L }

    /**
     * Which segment a timeline position falls in, and how far into that segment's own media it
     * is — i.e. exactly the `(mediaItemIndex, positionMs)` pair ExoPlayer wants.
     */
    fun positionInItem(timelineMs: Long): Pair<Int, Long> {
        if (isEmpty) return 0 to 0L
        val clamped = timelineMs.coerceIn(0L, totalDurationMs)
        // Last segment whose offset is at or before the requested position.
        var index = segments.lastIndex
        for (i in segments.indices) {
            if (offsets[i] > clamped) {
                index = i - 1
                break
            }
        }
        if (index < 0) index = 0
        val within = (clamped - offsets[index]).coerceIn(0L, segments[index].durationMs)
        return index to within
    }

    /** Timeline position → wall-clock time, honouring the real gaps between segments. */
    fun toWallClock(timelineMs: Long): Long {
        if (isEmpty) return 0L
        val (index, within) = positionInItem(timelineMs)
        return segments[index].startedAtMs + within
    }

    /**
     * Wall-clock time → timeline position. A time inside a gap between two segments has no frame
     * of its own; both edges of a gap collapse onto the same playable instant, so it resolves to
     * the boundary between them. Times outside the buffer clamp to its ends.
     */
    fun toTimeline(wallClockMs: Long): Long {
        if (isEmpty) return 0L
        if (wallClockMs <= startWallClockMs) return 0L
        if (wallClockMs >= endWallClockMs) return totalDurationMs

        segments.forEachIndexed { i, seg ->
            if (wallClockMs < seg.startedAtMs) return offsets[i] // in the gap before this segment
            if (wallClockMs < seg.endedAtMs) return offsets[i] + (wallClockMs - seg.startedAtMs)
        }
        return totalDurationMs
    }

    /** A 0..1 fraction of the timeline → wall-clock time. Convenience for slider-driven UI. */
    fun fractionToWallClock(fraction: Float): Long =
        toWallClock((totalDurationMs * fraction.coerceIn(0f, 1f)).toLong())
}
