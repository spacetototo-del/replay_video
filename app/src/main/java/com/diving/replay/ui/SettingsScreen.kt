package com.diving.replay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.diving.replay.Constants
import com.diving.replay.data.CaptureSettingsRepository
import com.diving.replay.data.IdleScreenMode
import com.diving.replay.data.TargetResolution
import kotlinx.coroutines.launch

/**
 * Resolution / bitrate / buffer-length (plan §0.5, §4 Phase 6). Changing resolution or bitrate
 * makes RecordingService wipe + restart the buffer (segments with different encoder settings
 * can't be concatenated) — see 의사결정.md.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { CaptureSettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val settings by repo.settings.collectAsState(initial = null)
    val s = settings

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Button(onClick = onBack) { Text("Back to live") }

        Text("Resolution", modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        val currentRes = s?.resolution ?: TargetResolution.FHD_1080P
        TargetResolution.entries.forEach { res ->
            Row(
                Modifier
                    .selectable(selected = res == currentRes, onClick = { scope.launch { repo.setResolution(res) } })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = res == currentRes, onClick = { scope.launch { repo.setResolution(res) } })
                Text("${res.label}  ·  default ~${res.defaultBitRateBps / 1_000_000} Mbps")
            }
        }

        Text(
            "Bitrate: ${((s?.bitRateBps ?: currentRes.defaultBitRateBps) / 1_000_000)} Mbps",
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        Slider(
            value = ((s?.bitRateBps ?: currentRes.defaultBitRateBps) / 1_000_000).toFloat(),
            valueRange = 2f..40f,
            steps = 37,
            onValueChange = { scope.launch { repo.setBitRate((it.toInt()) * 1_000_000) } },
        )

        Text("Buffer length", modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        val currentBuffer = s?.bufferRetentionMs ?: Constants.BUFFER_RETENTION_MS
        Constants.BUFFER_PRESETS_MS.forEach { preset ->
            Row(
                Modifier
                    .selectable(selected = preset == currentBuffer, onClick = { scope.launch { repo.setBufferRetentionMs(preset) } })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = preset == currentBuffer, onClick = { scope.launch { repo.setBufferRetentionMs(preset) } })
                Text("${preset / 60_000} min")
            }
        }

        Text("Idle screen", modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        val currentIdle = s?.idleScreen ?: IdleScreenMode.ALWAYS_BRIGHT
        IdleScreenMode.entries.forEach { mode ->
            Row(
                Modifier
                    .selectable(selected = mode == currentIdle, onClick = { scope.launch { repo.setIdleScreen(mode) } })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mode == currentIdle, onClick = { scope.launch { repo.setIdleScreen(mode) } })
                Text(mode.label)
            }
        }
        Text(
            "Recording never stops — this only changes the display. After 15s with no screen touch it dims (or turns off); any touch, or a watch REC signal, snaps it back to full brightness.",
            modifier = Modifier.padding(top = 4.dp),
        )

        Text(
            "Older footage is auto-deleted. Changing resolution or bitrate resets the current buffer.",
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
