package com.diving.replay.camera

import java.io.File

/**
 * One finished buffer segment on disk.
 *
 * @param file          the .mp4 file
 * @param startedAtMs    wall-clock time (System.currentTimeMillis) the segment started recording
 * @param durationMs     measured duration once finalized; 0 while still recording
 */
data class Segment(
    val file: File,
    val startedAtMs: Long,
    val durationMs: Long,
) {
    val endedAtMs: Long get() = startedAtMs + durationMs
    fun contains(timestampMs: Long): Boolean =
        timestampMs in startedAtMs until (startedAtMs + durationMs).coerceAtLeast(startedAtMs + 1)
}
