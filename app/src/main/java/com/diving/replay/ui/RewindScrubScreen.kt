package com.diving.replay.ui

import android.util.Log
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.diving.replay.camera.RecordingService
import com.diving.replay.playback.SegmentPlaylistPlayer
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Drag-to-scrub over the buffered footage (plan §0.3, §4 Phase 2).
 *
 * The timeline is snapshotted on entry so it doesn't shift under the finger while new segments
 * are still being recorded. Flow: drag the bar to preview frames → let go and it plays forward
 * from there so you can confirm the spot → tap "Set start" / "Set end" at the two moments you
 * want → "Save start–end" exports just that span. Tap the video to pause/resume.
 */
@UnstableApi
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RewindScrubScreen(
    service: RecordingService,
    onBackToLive: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val player = remember { SegmentPlaylistPlayer(context) }
    val fps by service.captureFps.collectAsState()
    val frameMs = (1000L / fps).coerceAtLeast(1L)

    var scrub by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var startFraction by remember { mutableStateOf<Float?>(null) }
    var endFraction by remember { mutableStateOf<Float?>(null) }
    var totalMs by remember { mutableLongStateOf(0L) }
    var loopAb by remember { mutableStateOf(false) }

    // Playback-side zoom: inspect entry angle without re-shooting. Separate from the live
    // camera zoom — this only magnifies the decoded frame, it changes nothing on disk.
    var viewScale by remember { mutableFloatStateOf(1f) }
    var viewOffsetX by remember { mutableFloatStateOf(0f) }
    var viewOffsetY by remember { mutableFloatStateOf(0f) }
    // width/height of the decoded video — used to letterbox instead of stretching to the box.
    var videoAspect by remember { mutableFloatStateOf(9f / 16f) }
    val density = LocalDensity.current

    fun seekTimeline(ms: Long) {
        val clamped = ms.coerceIn(0L, totalMs)
        player.seekToTimeline(clamped)
        scrub = if (totalMs > 0) clamped.toFloat() / totalMs else 0f
    }

    fun stepFrame(dir: Int) {
        if (player.isPlaying) player.pause()
        playing = false
        seekTimeline(player.currentTimelineMs() + dir * frameMs)
    }

    DisposableEffect(Unit) {
        // Freeze pruning while this screen is open, so the range the user picks is still on disk
        // when they hit save.
        service.holdBuffer()
        val sizeListener = object : Player.Listener {
            override fun onVideoSizeChanged(vs: VideoSize) {
                val w = (vs.width * vs.pixelWidthHeightRatio).toInt()
                if (w > 0 && vs.height > 0) videoAspect = w.toFloat() / vs.height
            }
        }
        player.exoPlayer.addListener(sizeListener)
        // snapshot the buffer once, on entry
        runCatching {
            player.load(service.segments.value)
            totalMs = player.totalDurationMs
            player.seekToTimeline(totalMs)
        }.onFailure { Log.e("RewindScrub", "player load failed", it) }
        onDispose {
            player.exoPlayer.removeListener(sizeListener)
            service.releaseBuffer()
            runCatching { player.release() }
        }
    }

    // While playing, let the bar follow the playhead (unless a finger is on it). With A–B on,
    // the playhead is pinned to the [start, end] span and the loop never ends on its own.
    LaunchedEffect(playing, loopAb) {
        while (playing) {
            if (!dragging) {
                val a = startFraction
                val b = endFraction
                if (loopAb && a != null && b != null) {
                    val lo = minOf(a, b)
                    val hi = maxOf(a, b)
                    val frac = player.timelineFraction()
                    if (frac >= hi || frac < lo - 0.02f || player.atEnd()) {
                        seekTimeline((totalMs * lo).toLong())
                        player.setSpeed(speed)
                        player.play()
                        delay(250) // let the async seek land, or the next check re-wraps at A
                        continue
                    }
                    if (!player.isPlaying) player.play() // resume a stall, don't end the loop
                    scrub = frac
                } else {
                    scrub = player.timelineFraction()
                    if (player.atEnd() || !player.isPlaying) {
                        playing = false
                        break
                    }
                }
            }
            delay(100)
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (totalMs <= 0L) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("아직 녹화된 영상이 없어요 — 잠시 녹화되게 두고 다시 오세요.")
            }
            Button(modifier = Modifier.fillMaxWidth(), onClick = onBackToLive) { Text("라이브로 돌아가기") }
        } else {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
                    // Two fingers pan/zoom the frame; a single tap toggles playback. Splitting on
                    // pointer count keeps the two from stealing each other's gestures — same
                    // approach as the live screen's pinch-vs-swipe.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var multiTouch = false
                            var moved = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                if (pressed == 0) break
                                if (pressed >= 2) {
                                    multiTouch = true
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    viewScale = (viewScale * zoomChange).coerceIn(1f, 6f)
                                    if (viewScale > 1f) {
                                        viewOffsetX += panChange.x
                                        viewOffsetY += panChange.y
                                    } else {
                                        viewOffsetX = 0f
                                        viewOffsetY = 0f
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (!multiTouch) {
                                    val d = event.changes.firstOrNull()?.positionChange()
                                    if (d != null && (abs(d.x) > 4f || abs(d.y) > 4f)) moved = true
                                }
                            }
                            if (!multiTouch && !moved) {
                                if (player.isPlaying) {
                                    player.pause()
                                    playing = false
                                } else {
                                    player.play()
                                    playing = true
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Fit the video inside the box at its real aspect ratio (letterbox), not stretched.
                val bw = constraints.maxWidth.toFloat()
                val bh = constraints.maxHeight.toFloat()
                val fitByHeight = bw / bh > videoAspect
                val vwPx = if (fitByHeight) bh * videoAspect else bw
                val vhPx = if (fitByHeight) bh else bw / videoAspect
                AndroidView(
                    modifier = Modifier
                        .width(with(density) { vwPx.toDp() })
                        .height(with(density) { vhPx.toDp() })
                        .graphicsLayer {
                            scaleX = viewScale
                            scaleY = viewScale
                            translationX = viewOffsetX
                            translationY = viewOffsetY
                        },
                    factory = { ctx ->
                        SurfaceView(ctx).also { sv -> player.exoPlayer.setVideoSurfaceView(sv) }
                    },
                )
            }

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
                    append("${posSec}초 / ${totalSec}초  ·  ${fps}fps")
                    startFraction?.let { append("   ·   시작 ${(totalMs * it / 1000).toInt()}초") }
                    endFraction?.let { append("   ·   끝 ${(totalMs * it / 1000).toInt()}초") }
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // playback speed + single-frame step
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(1f to "1배", 0.5f to "0.5배", 0.25f to "0.25배").forEach { (sp, label) ->
                    ChoiceChip(label, sp == speed) { speed = sp; player.setSpeed(sp) }
                }
                ChoiceChip("◀ 프레임", false) { stepFrame(-1) }
                ChoiceChip("프레임 ▶", false) { stepFrame(1) }

                if (startFraction != null && endFraction != null) {
                    ChoiceChip(if (loopAb) "↻ A–B 반복 켜짐" else "↻ A–B 반복", loopAb) {
                        if (loopAb) {
                            loopAb = false
                        } else {
                            loopAb = true
                            val a = startFraction
                            val b = endFraction
                            if (a != null && b != null) {
                                seekTimeline((totalMs * minOf(a, b)).toLong())
                                player.setSpeed(speed)
                                player.play()
                                playing = true
                            }
                        }
                    }
                }
                if (viewScale > 1f) {
                    ChoiceChip("줌 초기화 ${"%.1f".format(viewScale)}×", false) {
                        viewScale = 1f; viewOffsetX = 0f; viewOffsetY = 0f
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        startFraction = scrub
                    },
                ) { Text("시작점") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        endFraction = scrub
                    },
                ) { Text("끝점") }
            }

            Button(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                enabled = startFraction != null && endFraction != null,
                onClick = {
                    val a = startFraction ?: return@Button
                    val b = endFraction ?: return@Button
                    // Convert through the timeline so the cut lands on the real wall-clock
                    // instant of that frame, not on "start + cumulative duration".
                    service.exportManual(
                        player.wallClockAtFraction(minOf(a, b)),
                        player.wallClockAtFraction(maxOf(a, b)),
                        speed,
                    )
                    onBackToLive()
                },
            ) { Text(if (speed == 1f) "구간 저장" else "구간 저장 (${speed}배속 슬로모)") }

            Button(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = onBackToLive) {
                Text("라이브로")
            }
        }
    }
}
