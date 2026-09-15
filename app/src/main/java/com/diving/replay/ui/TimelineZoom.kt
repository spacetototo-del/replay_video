package com.diving.replay.ui

/**
 * Pure math for a zoomable, pannable scrub timeline (RewindScrubScreen — pinch the track to zoom
 * in on a long buffer, two-finger drag to pan). Kept Compose-free so the anchor/clamp math is
 * covered by a plain JVM unit test (see TimelineZoomTest) instead of only ever being exercised by
 * a finger on a device.
 *
 * All "buffer" fractions are 0f..1f positions in the whole recorded buffer (what `scrub` /
 * `startFraction` / `endFraction` already use elsewhere in RewindScrubScreen). "View" fractions
 * are 0f..1f positions within the currently *visible* window, i.e. what a finger position on the
 * track maps to.
 */
data class TimelineZoom(val zoom: Float = 1f, val center: Float = 0.5f) {

    /** Width of the visible window, as a fraction of the whole buffer. */
    val span: Float get() = (1f / zoom).coerceIn(MIN_SPAN, 1f)
    val start: Float get() = (center - span / 2f).coerceIn(0f, 1f - span)
    val end: Float get() = start + span

    /** Buffer fraction -> position in the visible window (outside 0f..1f if not visible). */
    fun toView(bufferFraction: Float): Float = (bufferFraction - start) / span

    /** Position in the visible window (0f..1f) -> buffer fraction. */
    fun toBuffer(viewFraction: Float): Float = (start + viewFraction * span).coerceIn(0f, 1f)

    fun isVisible(bufferFraction: Float): Boolean = bufferFraction in start..end

    /**
     * Pinch by [factor] (>1 = fingers spreading = zoom in) anchored at [anchorView] — the buffer
     * instant under the fingers stays under the fingers instead of the view jumping.
     */
    fun zoomedBy(factor: Float, anchorView: Float): TimelineZoom {
        val anchorBuffer = toBuffer(anchorView)
        val newZoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val newSpan = (1f / newZoom).coerceIn(MIN_SPAN, 1f)
        val newCenter = (anchorBuffer - (anchorView - 0.5f) * newSpan)
            .coerceIn(newSpan / 2f, 1f - newSpan / 2f)
        return TimelineZoom(newZoom, newCenter)
    }

    /** Slide the visible window by a raw drag of [viewDelta] (finger movement as a fraction of
     *  the track width) — content follows the finger, like scrolling a list: dragging right
     *  reveals earlier buffer content, so the window's centre moves the other way. */
    fun pannedBy(viewDelta: Float): TimelineZoom {
        val newCenter = (center - viewDelta * span).coerceIn(span / 2f, 1f - span / 2f)
        return copy(center = newCenter)
    }

    /** Re-centre the window on [bufferFraction] without changing zoom — used to keep the
     *  playhead on-screen while it plays out of a zoomed-in view. */
    fun centeredOn(bufferFraction: Float): TimelineZoom =
        copy(center = bufferFraction.coerceIn(span / 2f, 1f - span / 2f))

    fun reset(): TimelineZoom = TimelineZoom()

    companion object {
        const val MAX_ZOOM = 12f
        private const val MIN_SPAN = 1f / MAX_ZOOM
    }
}
