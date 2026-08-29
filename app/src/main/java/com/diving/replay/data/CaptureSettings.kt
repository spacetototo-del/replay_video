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
)

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
}
