package com.diving.replay

/** Shared constants for the phone app. Watch-side mirrors the message paths. */
object Constants {

    /** Bump this every build the user tests. Mirrored in app_name (strings.xml) and versionName. */
    const val VERSION_LABEL = "v3"

    // ---- Wear Data Layer message paths (watch -> phone) ----
    const val PATH_MARK_START = "/mark_start"
    const val PATH_MARK_END = "/mark_end"
    // phone -> watch acknowledgements
    const val PATH_ACK_SAVED = "/ack_saved"
    const val PATH_ACK_PARTIAL = "/ack_partial"
    const val PATH_ACK_ERROR = "/ack_error"

    // ---- Rolling buffer ----
    /** How much footage the buffer keeps available. Plan §0.2 / §4 Phase 1. */
    const val BUFFER_RETENTION_MS = 3 * 60 * 1000L
    /** Extra margin kept on disk beyond the advertised 3 min so a STOP that lands
     *  right on the edge still has the head of the clip. Plan §4 Phase 1. */
    const val BUFFER_SAFETY_MARGIN_MS = 60 * 1000L
    const val BUFFER_TOTAL_MS = BUFFER_RETENTION_MS + BUFFER_SAFETY_MARGIN_MS

    /** Segment length. 15s = balance of scrub precision vs. file-count overhead. Plan §12. */
    const val SEGMENT_DURATION_MS = 15_000L

    /** Buffer retention presets offered in Settings (ms). */
    val BUFFER_PRESETS_MS = longArrayOf(2 * 60_000L, 3 * 60_000L, 5 * 60_000L)

    const val SEGMENT_DIR = "buffer"
    const val EXPORT_RELATIVE_DIR = "Movies/DivingReplay"

    // ---- Screen wake (plan §11) ----
    /** No screen touch (or watch REC) for this long → the screen dims / turns off. Any touch
     *  snaps it back to full brightness and restarts the timer. */
    const val SCREEN_IDLE_DIM_MS = 15_000L

    // ---- Exported clip retention (plan §10 / §13 Q3) ----
    /** Keep the most recent N exported clips; older ones are offered for cleanup. */
    const val EXPORTED_CLIP_KEEP_COUNT = 50
}
