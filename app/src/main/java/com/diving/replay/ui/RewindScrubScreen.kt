package com.diving.replay.ui

import android.util.Log
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import com.diving.replay.camera.RecordingService
import com.diving.replay.playback.SegmentPlaylistPlayer
import kotlinx.coroutines.delay

/**
 * Drag-to-scrub over the buffered footage (plan §0.3, §4 Phase 2).
 *
 * The timeline is snapshotted on entry so it doesn't shift under the finger while new segments
 * are still being recorded. Flow: drag the bar to preview frames → let go and it plays forward
 * from there so you can confirm the spot → tap "Set start" / "Set end" at the two moments you
 * want → "Save start–end" exports just that span. Tap the video to pause/resume.
 */
@UnstableApi
@Composable
fun RewindScrubScreen(
    service: RecordingService,
    onBackToLive: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember { SegmentPlaylistPlayer(context) }

    var scrub by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var startFraction by remember { mutableStateOf<Float?>(null) }
    var endFraction by remember { mutableStateOf<Float?>(null) }
    var totalMs by remember { mutableLongStateOf(0L) }
    var timelineStartMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        // snapshot the buffer once, on entry
        runCatching {
            player.load(service.segments.value)
            totalMs = player.totalDurationMs
            timelineStartMs = player.timelineStartMs
            player.seekToTimeline(totalMs)
        }.onFailure { Log.e("RewindScrub", "player load failed", it) }
        onDispose { runCatching { player.release() } }
    }

    // While playing, let the bar follow the playhead (unless a finger is on it).
    LaunchedEffect(playing) {
        while (playing) {
            if (!dragging) {
                scrub = player.timelineFraction()
                if (player.atEnd() || !player.isPlaying) {
                    playing = false
                    break
                }
            }
            delay(100)
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (totalMs <= 0L) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No footage buffered yet — go back and let it record for a bit.")
            }
            Button(modifier = Modifier.fillMaxWidth(), onClick = onBackToLive) { Text("Back to live") }
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            if (player.isPlaying) {
                                player.pause()
                                playing = false
                            } else {
                                player.play()
                                playing = true
                            }
                        }
                    },
                factory = { ctx ->
                    SurfaceView(ctx).also { sv -> player.exoPlayer.setVideoSurfaceView(sv) }
                },
            )

            // Fixed-height track so the marker overlays can't stretch the layout.
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                val usable = maxWidth - 2.dp
                Slider(
                    modifier = Modifier.align(Alignment.Center),
                    value = scrub,
                    onValueChange = {
                        dragging = true
                        if (player.isPlaying) player.pause()
                        playing = false
                        scrub = it
                        player.seekToTimeline((totalMs * it).toLong())
                    },
                    onValueChangeFinished = {
                        dragging = false
                        player.play()
                        playing = true
                    },
                )
                startFraction?.let {
                    Box(
                        Modifier
                            .offset(x = usable * it)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF4CAF50)),
                    )
                }
                endFraction?.let {
                    Box(
                        Modifier
                            .offset(x = usable * it)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(Color(0xFFF44336)),
                    )
                }
            }

            val posSec = (totalMs * scrub / 1000).toInt()
            val totalSec = (totalMs / 1000).toInt()
            Text(
                buildString {
                    append("position ${posSec}s / ${totalSec}s")
                    startFraction?.let { append("   ·   start ${(totalMs * it / 1000).toInt()}s") }
                    endFraction?.let { append("   ·   end ${(totalMs * it / 1000).toInt()}s") }
                },
            )

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { startFraction = scrub }) {
                    Text("Set start")
                }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { endFraction = scrub }) {
                    Text("Set end")
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                enabled = startFraction != null && endFraction != null,
                onClick = {
                    val a = startFraction ?: return@Button
                    val b = endFraction ?: return@Button
                    val lo = minOf(a, b)
                    val hi = maxOf(a, b)
                    service.exportManual(
                        timelineStartMs + (totalMs * lo).toLong(),
                        timelineStartMs + (totalMs * hi).toLong(),
                    )
                    onBackToLive()
                },
            ) { Text("Save start–end") }

            Button(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = onBackToLive) {
                Text("Back to live")
            }
        }
    }
}
