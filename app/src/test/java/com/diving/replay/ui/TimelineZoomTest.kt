package com.diving.replay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gesture code in RewindScrubScreen can only really be checked with a finger on a device —
 * this locks down the anchor/clamp math it relies on so a regression shows up here first.
 */
class TimelineZoomTest {

    private val EPS = 1e-4f

    @Test
    fun `default is unzoomed and spans the whole buffer`() {
        val tz = TimelineZoom()
        assertEquals(1f, tz.zoom, EPS)
        assertEquals(0f, tz.start, EPS)
        assertEquals(1f, tz.end, EPS)
        assertEquals(0.3f, tz.toBuffer(0.3f), EPS)
        assertEquals(0.3f, tz.toView(0.3f), EPS)
    }

    @Test
    fun `zooming in around the centre halves the span symmetrically`() {
        val tz = TimelineZoom().zoomedBy(factor = 2f, anchorView = 0.5f)
        assertEquals(2f, tz.zoom, EPS)
        assertEquals(0.25f, tz.start, EPS)
        assertEquals(0.75f, tz.end, EPS)
    }

    @Test
    fun `pinch keeps the buffer instant under the fingers in the same view position`() {
        // Pinch off-centre, near the start of the buffer.
        val before = TimelineZoom()
        val anchorView = 0.2f
        val anchorBuffer = before.toBuffer(anchorView)

        val after = before.zoomedBy(factor = 3f, anchorView = anchorView)

        assertEquals(anchorBuffer, after.toBuffer(anchorView), EPS)
        assertEquals(anchorView, after.toView(anchorBuffer), EPS)
    }

    @Test
    fun `zoom is clamped to the configured range`() {
        val zoomedOut = TimelineZoom(zoom = 1f).zoomedBy(factor = 0.1f, anchorView = 0.5f)
        assertEquals(1f, zoomedOut.zoom, EPS) // can't zoom out past 1x (whole buffer)

        val zoomedIn = TimelineZoom().zoomedBy(factor = 1000f, anchorView = 0.5f)
        assertEquals(TimelineZoom.MAX_ZOOM, zoomedIn.zoom, EPS)
    }

    @Test
    fun `panning right (finger drags right) reveals earlier buffer content`() {
        val zoomed = TimelineZoom().zoomedBy(factor = 4f, anchorView = 0.5f) // span 0.25, centre 0.5
        val panned = zoomed.pannedBy(viewDelta = 0.5f) // drag half the visible window's width
        assertTrue("centre should move toward the start of the buffer", panned.center < zoomed.center)
    }

    @Test
    fun `pan is clamped so the window can't run off either end of the buffer`() {
        val zoomed = TimelineZoom().zoomedBy(factor = 4f, anchorView = 0.5f)
        // Drag way right (content follows the finger) -> reveals all the way back to the start.
        val pannedFarRight = zoomed.pannedBy(viewDelta = 10f)
        assertEquals(0f, pannedFarRight.start, EPS)
        // Drag way left -> reveals all the way to the newest footage.
        val pannedFarLeft = zoomed.pannedBy(viewDelta = -10f)
        assertEquals(1f, pannedFarLeft.end, EPS)
    }

    @Test
    fun `isVisible matches the visible window`() {
        val tz = TimelineZoom().zoomedBy(factor = 4f, anchorView = 0.5f) // 0.375..0.625
        assertTrue(tz.isVisible(0.5f))
        assertFalse(tz.isVisible(0.1f))
        assertFalse(tz.isVisible(0.9f))
    }

    @Test
    fun `centeredOn re-centres without changing zoom`() {
        val tz = TimelineZoom().zoomedBy(factor = 4f, anchorView = 0.5f)
        val recentred = tz.centeredOn(0.9f)
        assertEquals(tz.zoom, recentred.zoom, EPS)
        assertTrue(recentred.isVisible(0.9f))
    }

    @Test
    fun `reset returns to 1x`() {
        val tz = TimelineZoom().zoomedBy(factor = 5f, anchorView = 0.3f).pannedBy(0.2f)
        val reset = tz.reset()
        assertEquals(1f, reset.zoom, EPS)
        assertEquals(0f, reset.start, EPS)
        assertEquals(1f, reset.end, EPS)
    }
}
