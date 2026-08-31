package com.diving.replay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** User-adjustable capture settings (plan §4 Phase 6). Backed by Preferences DataStore. */
data class CaptureSettings(
    val resolution: TargetResolution = TargetResolution.FHD_1080P,
    val bitRateBps: Int = TargetResolution.FHD_1080P.defaultBitRateBps,
    val bufferRetentionMs: Long = com.diving.replay.Constants.BUFFER_RETENTION_MS,
    val idleScreen: IdleScreenMode = IdleScreenMode.DIM,
    /** Buffer capture frame rate. 60 = smooth 0.5x slow-mo on playback. 120 = best-effort. */
    val captureFps: Int = 60,
    /** How far behind live the delayed-replay screen runs — long enough to climb out and look. */
    val replayDelayMs: Long = 25_000L,
    /** Seconds with no screen touch before the display dims / turns off (DIM / SCREEN_OFF modes). */
    val dimAfterSec: Int = 30,
)

val CAPTURE_FPS_OPTIONS = listOf(30, 60, 120)

val REPLAY_DELAY_OPTIONS_MS = listOf(10_000L, 15_000L, 25_000L, 40_000L, 60_000L)

val DIM_AFTER_OPTIONS_SEC = listOf(10, 15, 30, 60, 120)

enum class TargetResolution(val label: String, val width: Int, val height: Int, val defaultBitRateBps: Int) {
    HD_720P("720p", 1280, 720, 4_000_000),
    FHD_1080P("1080p", 1920, 1080, 8_000_000),
    UHD_2160P("4K", 3840, 2160, 35_000_000),
}

/**
 * What the screen does while nothing is happening (plan §11). The camera keeps recording in all
 * three modes — this only changes the display. A watch REC signal snaps back to full brightness.
 */
enum class IdleScreenMode(val label: String) {
    ALWAYS_BRIGHT("Always bright"),
    DIM("Dim when idle"),
    SCREEN_OFF("Screen off when idle"),
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "capture_settings")

class CaptureSettingsRepository(private val context: Context) {

    private object Keys {
        val RESOLUTION = intPreferencesKey("resolution_ordinal")
        val BITRATE = intPreferencesKey("bitrate_bps")
        val BUFFER_MS = longPreferencesKey("buffer_retention_ms")
        val IDLE_SCREEN = intPreferencesKey("idle_screen_ordinal")
        val CAPTURE_FPS = intPreferencesKey("capture_fps")
        val REPLAY_DELAY_MS = longPreferencesKey("replay_delay_ms")
        val DIM_AFTER_SEC = intPreferencesKey("dim_after_sec")
    }

    val settings: Flow<CaptureSettings> = context.dataStore.data.map { prefs ->
        val res = TargetResolution.entries.getOrElse(
            prefs[Keys.RESOLUTION] ?: TargetResolution.FHD_1080P.ordinal,
        ) { TargetResolution.FHD_1080P }
        CaptureSettings(
            resolution = res,
            bitRateBps = prefs[Keys.BITRATE] ?: res.defaultBitRateBps,
            bufferRetentionMs = prefs[Keys.BUFFER_MS] ?: com.diving.replay.Constants.BUFFER_RETENTION_MS,
            idleScreen = IdleScreenMode.entries.getOrElse(
                prefs[Keys.IDLE_SCREEN] ?: IdleScreenMode.DIM.ordinal,
            ) { IdleScreenMode.DIM },
            captureFps = (prefs[Keys.CAPTURE_FPS] ?: 60).takeIf { it in CAPTURE_FPS_OPTIONS } ?: 60,
            replayDelayMs = prefs[Keys.REPLAY_DELAY_MS] ?: 25_000L,
            dimAfterSec = (prefs[Keys.DIM_AFTER_SEC] ?: 30).takeIf { it in DIM_AFTER_OPTIONS_SEC } ?: 30,
        )
    }

    suspend fun setResolution(res: TargetResolution) = context.dataStore.edit { prefs ->
        prefs[Keys.RESOLUTION] = res.ordinal
        // Reset bitrate to the new resolution's default unless the user overrode it later.
        prefs[Keys.BITRATE] = res.defaultBitRateBps
    }

    suspend fun setBitRate(bps: Int) = context.dataStore.edit { it[Keys.BITRATE] = bps }

    suspend fun setBufferRetentionMs(ms: Long) = context.dataStore.edit { it[Keys.BUFFER_MS] = ms }

    suspend fun setIdleScreen(mode: IdleScreenMode) = context.dataStore.edit { it[Keys.IDLE_SCREEN] = mode.ordinal }

    suspend fun setCaptureFps(fps: Int) = context.dataStore.edit { it[Keys.CAPTURE_FPS] = fps }

    suspend fun setReplayDelayMs(ms: Long) = context.dataStore.edit { it[Keys.REPLAY_DELAY_MS] = ms }

    suspend fun setDimAfterSec(sec: Int) = context.dataStore.edit { it[Keys.DIM_AFTER_SEC] = sec }
}
