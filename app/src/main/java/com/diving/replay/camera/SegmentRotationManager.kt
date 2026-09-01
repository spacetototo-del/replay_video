package com.diving.replay.camera

import android.util.Log
import com.diving.replay.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the on-disk ring of buffer segments (plan §4 Phase 1).
 *
 * - [register] is called each time CameraX finalizes a segment file.
 * - Anything whose *end* is older than [retentionMs] + safety margin is deleted.
 * - [segments] exposes the currently-live window for the playlist player and exporter.
 *
 * Thread-safety: register/prune can be called from the CameraX callback thread while the
 * UI reads [segments]; a lock guards the mutable list and StateFlow publishes immutable copies.
 */
class SegmentRotationManager(
    private val bufferDir: File,
    @Volatile var retentionMs: Long = Constants.BUFFER_RETENTION_MS,
) {
    private val lock = ReentrantLock()
    private val live = ArrayDeque<Segment>()

    /**
     * While > 0, pruning is suspended: the rewind screen snapshots the buffer on entry and cuts
     * an export against those exact files, so deleting them out from under it turns a "save this
     * 8-second dive" into "saved 2 seconds (partial)". Held only while that screen is open; a
     * catch-up prune runs on release. Balanced with [holdPrune]/[releasePrune].
     */
    @Volatile
    private var pruneHold = 0

    private val _segments = MutableStateFlow<List<Segment>>(emptyList())
    val segments: StateFlow<List<Segment>> = _segments.asStateFlow()

    init {
        bufferDir.mkdirs()
        // Start every session with an empty buffer. Segments from a previous run are separated
        // from "now" by an unknown gap, so keeping them would show bogus coverage and let the
        // scrubber jump into unrelated old footage.
        bufferDir.listFiles { f -> f.extension == "mp4" }?.forEach { it.delete() }
        publish()
    }

    fun newSegmentFile(startedAtMs: Long): File =
        File(bufferDir, "seg_${startedAtMs}.mp4")

    /** Call when a segment file is fully written. Triggers a prune pass. */
    fun register(file: File, startedAtMs: Long, durationMs: Long) = lock.withLock {
        // Replace the placeholder entry (durationMs == 0) if we pre-registered it.
        live.removeAll { it.file == file }
        live.addLast(Segment(file, startedAtMs, durationMs))
        pruneLocked(now = System.currentTimeMillis())
        publish()
    }

    /** Prune without a new segment (e.g. periodic tick). */
    fun prune(now: Long = System.currentTimeMillis()) = lock.withLock {
        pruneLocked(now)
        publish()
    }

    /** Suspend pruning (rewind screen open). Always pair with [releasePrune]. */
    fun holdPrune() = lock.withLock { pruneHold++ }

    /** Resume pruning and immediately catch up on anything that went stale while held. */
    fun releasePrune() = lock.withLock {
        if (pruneHold > 0) pruneHold--
        pruneLocked(System.currentTimeMillis())
        publish()
    }

    private fun pruneLocked(now: Long) {
        if (pruneHold > 0) return
        val cutoff = now - (retentionMs + Constants.BUFFER_SAFETY_MARGIN_MS)
        while (live.isNotEmpty()) {
            val head = live.first()
            val headEnd = if (head.durationMs > 0) head.endedAtMs else head.startedAtMs + Constants.SEGMENT_DURATION_MS
            if (headEnd < cutoff) {
                live.removeFirst()
                if (!head.file.delete()) {
                    Log.w(TAG, "could not delete stale segment ${head.file.name}")
                }
            } else {
                break
            }
        }
    }

    /** Immutable copy of the whole ring, oldest first. [ClipCutPlanner] narrows it to a window. */
    fun snapshot(): List<Segment> = lock.withLock { live.sortedBy { it.startedAtMs } }

    /** True if the requested start is older than what the buffer still holds (plan §4 Phase 3). */
    fun isStartTruncated(startMs: Long): Boolean = lock.withLock {
        val earliest = live.firstOrNull()?.startedAtMs ?: return true
        startMs < earliest
    }

    /** How much footage is currently scrubbable, in ms. */
    fun coverageMs(): Long = lock.withLock {
        val first = live.firstOrNull() ?: return 0
        val last = live.last()
        (last.endedAtMs - first.startedAtMs).coerceAtLeast(0)
    }

    fun clear() = lock.withLock {
        live.forEach { it.file.delete() }
        live.clear()
        publish()
    }

    private fun publish() {
        _segments.value = live.toList()
    }

    companion object {
        private const val TAG = "SegmentRotation"
    }
}
