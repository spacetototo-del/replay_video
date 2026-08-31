package com.diving.replay.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REC/STOP only ever move markers — the camera keeps rolling either way (plan §8). What matters
 * is that a pair always comes out ordered and that a stray STOP can't produce a bogus window.
 */
class MarkerManagerTest {

    @Test
    fun `REC then STOP yields the span between them`() {
        val m = MarkerManager()
        m.markStart(1_000)
        assertTrue(m.isArmed)

        val window = m.markEnd(6_000)

        assertEquals(1_000L to 6_000L, window)
        assertFalse("STOP disarms so the next REC starts fresh", m.isArmed)
    }

    @Test
    fun `STOP without REC produces nothing`() {
        val m = MarkerManager()
        assertNull(m.markEnd(6_000))
        assertFalse(m.isArmed)
    }

    @Test
    fun `a lead-in back-dates the start so the take-off is not lost`() {
        // Pressing REC once the diver is already airborne should still catch the approach.
        val m = MarkerManager()
        m.markStart(10_000, leadInMs = 5_000)
        assertEquals(5_000L to 12_000L, m.markEnd(12_000))
    }

    @Test
    fun `a lead-in cannot back-date past zero`() {
        val m = MarkerManager()
        m.markStart(2_000, leadInMs = 10_000)
        assertEquals(0L, m.start.value?.atMs)
    }

    @Test
    fun `a STOP that arrives at or before the start still yields an ordered span`() {
        val m = MarkerManager()
        m.markStart(5_000)
        val (start, end) = m.markEnd(4_000)!!
        assertTrue("end must follow start", end > start)
    }

    @Test
    fun `a second REC replaces the pending start`() {
        val m = MarkerManager()
        m.markStart(1_000)
        m.markStart(3_000)
        assertEquals(3_000L to 8_000L, m.markEnd(8_000))
    }

    @Test
    fun `cancel disarms without producing a window`() {
        val m = MarkerManager()
        m.markStart(1_000)
        m.cancel()
        assertFalse(m.isArmed)
        assertNull(m.markEnd(5_000))
    }
}
