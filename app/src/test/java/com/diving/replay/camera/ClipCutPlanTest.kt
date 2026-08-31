package com.diving.replay.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Cutting a wall-clock window out of the segment ring is the step that decides whether a saved
 * dive actually contains the dive. Verified here without a camera or a muxer.
 */
class ClipCutPlanTest {

    private fun seg(startMs: Long, durationMs: Long) =
        Segment(File("seg_$startMs.mp4"), startMs, durationMs)

    private val ring = listOf(
        seg(100_000, 15_000), // wall 100000..115000
        seg(115_300, 15_000), // wall 115300..130300
        seg(130_600, 15_000), // wall 130600..145600
    )

    @Test
    fun `a window inside one segment yields one trimmed cut`() {
        val plan = ClipCutPlanner.plan(ring, 105_000, 110_000)!!
        assertEquals(1, plan.cuts.size)
        val cut = plan.cuts.single()
        assertEquals(100_000L, cut.segment.startedAtMs)
        assertEquals(5_000L, cut.startInSegmentMs)
        assertEquals(10_000L, cut.endInSegmentMs)
        assertEquals(5_000L, plan.keptDurationMs)
    }

    @Test
    fun `a window across segments trims the ends and keeps the middle whole`() {
        val plan = ClipCutPlanner.plan(ring, 112_000, 133_600)!!
        assertEquals(3, plan.cuts.size)

        assertEquals(12_000L, plan.cuts[0].startInSegmentMs)
        assertEquals(ClipCut.KEEP_TO_END, plan.cuts[0].endInSegmentMs)

        assertEquals(0L, plan.cuts[1].startInSegmentMs)
        assertEquals(ClipCut.KEEP_TO_END, plan.cuts[1].endInSegmentMs)

        assertEquals(0L, plan.cuts[2].startInSegmentMs)
        assertEquals(3_000L, plan.cuts[2].endInSegmentMs)

        // 3s + 15s + 3s of media. Shorter than the 21.6s of wall time asked for, because the
        // 600ms of changeover gaps were never recorded.
        assertEquals(21_000L, plan.keptDurationMs)
    }

    @Test
    fun `a start older than the buffer is clamped to the oldest surviving frame`() {
        val plan = ClipCutPlanner.plan(ring, 10_000, 105_000)!!
        assertEquals(100_000L, plan.effectiveStartMs)
        assertEquals(105_000L, plan.effectiveEndMs)
        assertEquals(0L, plan.cuts.single().startInSegmentMs)
    }

    @Test
    fun `an end past the buffer is clamped to the newest frame`() {
        val plan = ClipCutPlanner.plan(ring, 144_000, 999_999)!!
        assertEquals(145_600L, plan.effectiveEndMs)
        assertEquals(13_400L, plan.cuts.single().startInSegmentMs)
        assertEquals(15_000L, plan.cuts.single().endInSegmentMs)
    }

    @Test
    fun `a window that misses the buffer entirely plans nothing`() {
        assertNull(ClipCutPlanner.plan(ring, 10_000, 20_000))
        assertNull(ClipCutPlanner.plan(ring, 200_000, 210_000))
        assertNull(ClipCutPlanner.plan(emptyList(), 100_000, 110_000))
    }

    @Test
    fun `a mis-tap shorter than the minimum plans nothing`() {
        assertNull(ClipCutPlanner.plan(ring, 105_000, 105_100))
        assertNotNull(ClipCutPlanner.plan(ring, 105_000, 105_000 + ClipCutPlanner.MIN_CLIP_MS))
    }

    @Test
    fun `a window landing in a changeover gap still resolves to real footage`() {
        // 115_000..115_300 was never recorded; the window has to come from the frames around it.
        val plan = ClipCutPlanner.plan(ring, 114_000, 116_000)!!
        assertEquals(2, plan.cuts.size)
        assertEquals(14_000L, plan.cuts[0].startInSegmentMs)
        assertEquals(ClipCut.KEEP_TO_END, plan.cuts[0].endInSegmentMs)
        assertEquals(700L, plan.cuts[1].endInSegmentMs)
        assertEquals(1_700L, plan.keptDurationMs)
    }

    @Test
    fun `unfinalised segments are never cut against`() {
        val withPending = ring + seg(145_900, 0)
        val plan = ClipCutPlanner.plan(withPending, 140_000, 999_999)!!
        assertEquals(1, plan.cuts.size)
        assertEquals(145_600L, plan.effectiveEndMs)
    }

    @Test
    fun `cuts come out oldest first regardless of input order`() {
        val plan = ClipCutPlanner.plan(ring.reversed(), 112_000, 133_600)!!
        assertEquals(listOf(100_000L, 115_300L, 130_600L), plan.cuts.map { it.segment.startedAtMs })
    }
}
