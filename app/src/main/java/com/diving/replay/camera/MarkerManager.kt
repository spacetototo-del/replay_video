package com.diving.replay.camera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records the REC / STOP marker timestamps that arrive from the watch (plan §8).
 *
 * The camera never actually starts or stops for these — it is always buffering. A REC just
 * remembers "from here", a STOP remembers "to here" and hands the pair to the exporter.
 */
class MarkerManager {

    data class Marker(val atMs: Long)

    private val _start = MutableStateFlow<Marker?>(null)
    val start: StateFlow<Marker?> = _start.asStateFlow()

    val isArmed: Boolean get() = _start.value != null

    /** REC pressed. [leadInMs] optionally back-dates the start (plan §8, "5s before" option). */
    fun markStart(atMs: Long = System.currentTimeMillis(), leadInMs: Long = 0L) {
        _start.value = Marker((atMs - leadInMs).coerceAtLeast(0))
    }

    /** STOP pressed. Returns the [start, end] pair and disarms, or null if REC was never pressed. */
    fun markEnd(atMs: Long = System.currentTimeMillis()): Pair<Long, Long>? {
        val s = _start.value ?: return null
        _start.value = null
        val end = atMs.coerceAtLeast(s.atMs + 1)
        return s.atMs to end
    }

    fun cancel() { _start.value = null }
}
