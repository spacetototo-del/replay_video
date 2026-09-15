package com.diving.replay.camera

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Appends a plain-text breadcrumb to a file in app storage whenever a save fails. logcat is gone
 * within minutes and the failure usually happens away from a computer (pool, gym) — this survives
 * long enough to be pulled and diagnosed later. Not a crash reporter and never touches the
 * network; the only way it leaves the phone is the user explicitly tapping "로그 공유" in Settings
 * (see [com.diving.replay.ui.SettingsScreen]), which shares it through the OS share sheet.
 */
object DiagnosticLog {
    private const val FILE_NAME = "export_errors.log"

    /** Trim once the file passes this size, keeping the most recent half — oldest failures
     *  matter least, and this keeps a share-sheet attachment from growing without bound. */
    private const val MAX_BYTES = 512_000L

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /**
     * @param event short label ("export failed", "nothing to save") so entries are greppable.
     * @param details free-form context — segment counts, buffer span, device/memory info; whatever
     *   helps reconstruct what the buffer looked like at the moment of failure.
     */
    fun logFailure(context: Context, event: String, details: Map<String, Any?>, error: Throwable? = null) {
        runCatching {
            val f = file(context)
            val entry = buildString {
                append("== ").append(TS_FORMAT.format(LocalDateTime.now())).append(" · ").append(event).append(" ==\n")
                details.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
                if (error != null) {
                    append(error.javaClass.name).append(": ").append(error.message).append('\n')
                    append(Log.getStackTraceString(error))
                }
                append('\n')
            }
            f.appendText(entry)
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast((MAX_BYTES / 2).toInt()))
        }.onFailure { Log.w(TAG, "failed to write diagnostic log", it) }
    }

    private val TS_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private const val TAG = "DiagnosticLog"
}
