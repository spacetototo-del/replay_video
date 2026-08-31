package com.diving.replay.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import com.diving.replay.camera.RecordingService
import com.diving.replay.data.CaptureSettingsRepository
import com.diving.replay.data.REPLAY_DELAY_OPTIONS_MS
import com.diving.replay.playback.SegmentPlaylistPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The poolside monitor mode: the screen continuously shows what the camera saw N seconds ago,
 * so a diver can surface, climb out, walk over and watch their own dive without touching
 * anything. This is the behaviour of the commercial systems the app is modelled on.
 *
 * It works by playing the same rolling buffer the rewind screen uses, but pinned to a moving
 * target of `now - delay` instead of a position the user picked. Every second we compare where
 * the playhead actually is against where it should be and nudge it if it has drifted — that
 * self-corrects the accumulated error from decode hiccups without ever restarting playback.
 */
@UnstableApi
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DelayedReplayScreen(
    service: RecordingService,
    onBackToLive: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { CaptureSettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val settings by repo.settings.collectAsState(initial = null)
    val delayMs = settings?.replayDelayMs ?: 25_000L

    val player = remember { SegmentPlaylistPlayer(context) }
    var behindLiveMs by remember { mutableLongStateOf(0L) }
    var coverageMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose { runCatching { player.release() } }
    }

    // The pacing loop. Re-launched when the delay changes so the new target takes effect at once.
    LaunchedEffect(delayMs) {
        while (true) {
            val segments = service.segments.value
            runCatching {
                player.sync(segments)
                val timeline = player.timeline
                coverageMs = timeline.endWallClockMs - timeline.startWallClockMs

                if (!timeline.isEmpty) {
                    val target = System.currentTimeMillis() - delayMs
                    val targetPos = timeline.toTimeline(target)
                    val drift = targetPos - player.currentTimelineMs()
                    if (abs(drift) > DRIFT_TOLERANCE_MS) player.seekToTimeline(targetPos)
                    if (!player.isPlaying) player.play()
                    behindLiveMs = timeline.endWallClockMs - timeline.toWallClock(player.currentTimelineMs())
                }
            }
            delay(TICK_MS)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> SurfaceView(ctx).also { player.exoPlayer.setVideoSurfaceView(it) } },
        )

        val warmingUp = coverageMs < delayMs
        Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
            StatusPill(
                if (warmingUp) {
                    "버퍼 채우는 중… ${coverageMs / 1000}초 / ${delayMs / 1000}초"
                } else {
                    "지연 재생 · 실시간보다 ${behindLiveMs / 1000}초 뒤"
                },
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .widthIn(max = DivingTokens.contentMaxWidth)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                REPLAY_DELAY_OPTIONS_MS.forEach { option ->
                    ChoiceChip("${option / 1000}초", option == delayMs) {
                        scope.launch { repo.setReplayDelayMs(option) }
                    }
                }
            }
            Button(modifier = Modifier.fillMaxWidth(), onClick = onBackToLive) { Text("라이브로") }
        }
    }
}

/** How often the playhead is checked against the moving target. */
private const val TICK_MS = 1_000L

/** Below this the correction would be more distracting than the drift. */
private const val DRIFT_TOLERANCE_MS = 1_500L
