package com.diving.replay.camera

/**
 * One segment's contribution to an exported clip: which slice of that file to keep.
 * Positions are relative to the segment's own media, which is what
 * `MediaItem.ClippingConfiguration` expects.
 */
data class ClipCut(
    val segment: Segment,
    val startInSegmentMs: Long,
    /** [KEEP_TO_END] means "play this segment through to its end". */
    val endInSegmentMs: Long,
) {
    val keptDurationMs: Long
        get() = (if (endInSegmentMs == KEEP_TO_END) segment.durationMs else endInSegmentMs) -
            startInSegmentMs

    companion object {
        const val KEEP_TO_END = -1L
    }
}

/**
 * The full recipe for one export: which slices, and the wall-clock span actually covered
 * (which can be narrower than requested if the buffer no longer reaches that far back).
 */
data class ClipPlan(
    val cuts: List<ClipCut>,
    val effectiveStartMs: Long,
    val effectiveEndMs: Long,
) {
    /** How long the finished clip will be — the sum of kept slices, gaps excluded. */
    val keptDurationMs: Long get() = cuts.sumOf { it.keptDurationMs }
}

/**
 * Works out how to cut a `[startMs, endMs]` wall-clock window out of the segment ring
 * (plan §4 Phase 3). Pure logic so it can be verified without a camera — see `ClipCutPlanTest`.
 *
 * Returns null when the window doesn't overlap anything usable, or is too short to be worth
 * exporting.
 */
object ClipCutPlanner {

    /** Anything shorter than this is a mis-tap, not a clip. */
    const val MIN_CLIP_MS = 300L

    fun plan(segments: List<Segment>, startMs: Long, endMs: Long): ClipPlan? {
        val overlapping = segments
            .filter { it.durationMs > 0 && it.startedAtMs < endMs && it.endedAtMs > startMs }
            .sortedBy { it.startedAtMs }
        if (overlapping.isEmpty()) return null

        val effectiveStart = maxOf(startMs, overlapping.first().startedAtMs)
        val effectiveEnd = minOf(endMs, overlapping.last().endedAtMs)
        if (effectiveEnd - effectiveStart < MIN_CLIP_MS) return null

        val cuts = overlapping.mapIndexed { index, seg ->
            val isFirst = index == 0
            val isLast = index == overlapping.lastIndex
            ClipCut(
                segment = seg,
                startInSegmentMs = if (isFirst) {
                    (effectiveStart - seg.startedAtMs).coerceIn(0L, seg.durationMs)
                } else {
                    0L
                },
                endInSegmentMs = if (isLast) {
                    (effectiveEnd - seg.startedAtMs).coerceIn(1L, seg.durationMs)
                } else {
                    ClipCut.KEEP_TO_END
                },
            )
        }
        return ClipPlan(cuts, effectiveStart, effectiveEnd)
    }
}
