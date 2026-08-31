package com.diving.replay.playback

import com.diving.replay.camera.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The buffer's segments are never gap-free — CameraX needs a moment between stopping one
 * recording and starting the next. These tests pin down that a scrub position maps to the real
 * wall-clock instant of that frame rather than to "buffer start + cumulative duration", which is
 * what used to silently shift saved clips further off the older the footage got.
 *
 * (Longs are compared with explicit `L` literals throughout: JUnit4's `assertEquals` would
 * otherwise resolve to the `(Object, Object)` overload and compare an Integer to a Long.)
 */
class SegmentTimelineTest {

    private fun seg(startMs: Long, durationMs: Long) =
        Segment(File("seg_$startMs.mp4"), startMs, durationMs)

    /** Three 15s segments with a 300ms changeover gap between each. */
    private val gappy = listOf(
        seg(100_000, 15_000), // plays 0..15000,     wall 100000..115000
        seg(115_300, 15_000), // plays 15000..30000, wall 115300..130300
        seg(130_600, 15_000), // plays 30000..45000, wall 130600..145600
    )

    @Test
    fun `total duration excludes the gaps between segments`() {
        val timeline = SegmentTimeline(gappy)
        assertEquals(45_000L, timeline.totalDurationMs)
        // ...even though the buffer spans 45.6s of wall time.
        assertEquals(45_600L, timeline.endWallClockMs - timeline.startWallClockMs)
    }

    @Test
    fun `timeline start of the third segment resolves to its real wall clock`() {
        val timeline = SegmentTimeline(gappy)
        // The naive "start + cumulative duration" answer would be 130_000 — 600ms early,
        // which is what made saved clips miss the moment.
        assertEquals(130_600L, timeline.toWallClock(30_000))
    }

    @Test
    fun `drift does not accumulate across segments`() {
        val timeline = SegmentTimeline(gappy)
        val naive = timeline.startWallClockMs + 44_000L
        val actual = timeline.toWallClock(44_000)
        assertEquals(144_600L, actual)
        assertEquals("the error the old contiguity assumption carried", 600L, actual - naive)
    }

    @Test
    fun `wall clock inside a segment round-trips back to the same timeline position`() {
        val timeline = SegmentTimeline(gappy)
        listOf(0L, 1L, 7_500L, 14_999L, 15_000L, 22_000L, 30_000L, 44_999L).forEach { pos ->
            assertEquals("round trip at $pos", pos, timeline.toTimeline(timeline.toWallClock(pos)))
        }
    }

    @Test
    fun `a wall clock inside a gap collapses onto the segment boundary`() {
        val timeline = SegmentTimeline(gappy)
        // 115_000 is the last instant of segment 0; 115_300 is the first of segment 1.
        // Nothing was recorded in between, so every time in the gap is the same playable frame.
        assertEquals(15_000L, timeline.toTimeline(115_000))
        assertEquals(15_000L, timeline.toTimeline(115_150))
        assertEquals(15_000L, timeline.toTimeline(115_300))
    }

    @Test
    fun `wall clock outside the buffer clamps to its ends`() {
        val timeline = SegmentTimeline(gappy)
        assertEquals(0L, timeline.toTimeline(1_000))
        assertEquals(45_000L, timeline.toTimeline(999_999))
    }

    @Test
    fun `positionInItem picks the right playlist entry and offset`() {
        val timeline = SegmentTimeline(gappy)
        assertEquals(0 to 0L, timeline.positionInItem(0))
        assertEquals(0 to 14_999L, timeline.positionInItem(14_999))
        assertEquals(1 to 0L, timeline.positionInItem(15_000))
        assertEquals(2 to 4_000L, timeline.positionInItem(34_000))
    }

    @Test
    fun `positions outside the timeline are clamped, not thrown`() {
        val timeline = SegmentTimeline(gappy)
        assertEquals(0 to 0L, timeline.positionInItem(-5_000))
        assertEquals(2 to 15_000L, timeline.positionInItem(99_999))
    }

    @Test
    fun `segments are sorted and unfinalised ones dropped`() {
        val timeline = SegmentTimeline(
            listOf(seg(130_600, 15_000), seg(100_000, 15_000), seg(150_000, 0)),
        )
        assertEquals(2, timeline.segments.size)
        assertEquals(100_000L, timeline.segments.first().startedAtMs)
        assertEquals(30_000L, timeline.totalDurationMs)
    }

    @Test
    fun `an empty buffer answers safely instead of crashing`() {
        val timeline = SegmentTimeline(emptyList())
        assertTrue(timeline.isEmpty)
        assertEquals(0L, timeline.totalDurationMs)
        assertEquals(0L, timeline.toWallClock(5_000))
        assertEquals(0L, timeline.toTimeline(123_456))
        assertEquals(0 to 0L, timeline.positionInItem(1_000))
    }

    @Test
    fun `fraction maps onto wall clock through the real timeline`() {
        val timeline = SegmentTimeline(gappy)
        assertEquals(100_000L, timeline.fractionToWallClock(0f))
        assertEquals(145_600L, timeline.fractionToWallClock(1f))
        // Two thirds in = the start of the third segment, 600ms later than naive maths says.
        assertEquals(130_600L, timeline.fractionToWallClock(2f / 3f))
    }
}
