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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
 *
 * The whole screen turns with the phone (RotatedEdge, [Alignment.Center]) — the activity is
 * portrait-locked, but a long buffer needs every pixel of track width it can get to pick a start
 * and end point precisely, and holding the phone sideways is exactly when there's more of it.
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
    val orientation = rememberUprightOrientation()

    var scrub by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var startFraction by remember { mutableStateOf<Float?>(null) }
    var endFraction by remember { mutableStateOf<Float?>(null) }
    var totalMs by remember { mutableLongStateOf(0L) }
    var loopAb by remember { mutableStateOf(false) }
    // Pinch-to-zoom on the *track* (not the video): lets a long buffer be scrubbed precisely
    // instead of every finger-width covering minutes of footage. See TimelineZoom for the math.
    var tz by remember { mutableStateOf(TimelineZoom()) }

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

    // Marking the start shouldn't freeze anything — the whole point is to then watch the
    // playhead keep moving right while looking for the end. If playback wasn't already running
    // (e.g. marked right after a paused frame-step), kick it off so there's something to watch.
    fun markStart(): Float {
        val frac = player.timelineFraction()
        scrub = frac
        if (!player.isPlaying) {
            player.setSpeed(speed)
            player.play()
            playing = true
        }
        return frac
    }

    // Marking the end is the opposite: pause immediately and read the position straight from the
    // player (not the ~100ms-stale `scrub` state the playback loop below updates), so the freeze
    // frame gives visible confirmation of exactly what got captured — instead of the video
    // sailing on past the spot while the mark silently landed a beat earlier.
    fun markEnd(): Float {
        if (player.isPlaying) player.pause()
        playing = false
        val frac = player.timelineFraction()
        scrub = frac
        return frac
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
                    // Only a real end-of-timeline stops the follow loop. `!player.isPlaying`
                    // used to stop it too, but ExoPlayer briefly reports isPlaying=false while
                    // it's still buffering right after a fresh play() (exactly what markStart()
                    // triggers) — that transient false tripped this and silently killed the loop
                    // a beat after playback actually started, leaving the playhead marker
                    // stranded at the old position while the video kept going. Explicit pauses
                    // (tap-to-pause, markEnd) already flip `playing` at their own call site, so
                    // they don't need this check to stop the loop.
                    if (player.atEnd()) {
                        playing = false
                        break
                    }
                }
                // Keep the playhead on-screen while zoomed in, instead of it running off the
                // edge of the visible window and just vanishing.
                if (!tz.isVisible(scrub)) tz = tz.centeredOn(scrub)
            }
            delay(100)
        }
    }

    // --- Reusable pieces. Landscape needs a different arrangement (video beside the controls,
    // not above them — see the layout picker at the bottom of this function), so everything past
    // the video is split into small local composables shared by both arrangements rather than
    // duplicated. They close over the state above like any other part of this composable.

    @Composable
    fun VideoArea(modifier: Modifier) {
        BoxWithConstraints(
            modifier
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
    }

    @Composable
    fun Track(modifier: Modifier) {
            // Fixed-height track so the marker overlays can't stretch the layout. One finger
            // scrubs (mapped through the current zoom window); two fingers pinch to zoom in on
            // the track or pan the zoomed window — same split-on-pointer-count approach as the
            // video gesture above, so it never steals the scrub gesture out from under it.
            BoxWithConstraints(
                modifier
                    .height(48.dp)
                    .pointerInput(totalMs) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var multiTouch = false
                            val widthPx = size.width.toFloat()
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                if (pressed == 0) break
                                if (pressed >= 2) {
                                    if (dragging) { // hand off from a single-finger scrub mid-gesture
                                        dragging = false
                                        player.play()
                                        playing = true
                                    }
                                    multiTouch = true
                                    if (widthPx > 0f) {
                                        val centroidX = event.changes.fold(0f) { s, c -> s + c.position.x } / pressed
                                        val anchorView = (centroidX / widthPx).coerceIn(0f, 1f)
                                        val zoomChange = event.calculateZoom()
                                        if (zoomChange != 1f) tz = tz.zoomedBy(zoomChange, anchorView)
                                        val panChange = event.calculatePan()
                                        if (panChange.x != 0f) tz = tz.pannedBy(panChange.x / widthPx)
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (!multiTouch && widthPx > 0f) {
                                    if (!dragging) {
                                        dragging = true
                                        if (player.isPlaying) player.pause()
                                        playing = false
                                    }
                                    val x = event.changes.first().position.x.coerceIn(0f, widthPx)
                                    scrub = tz.toBuffer(x / widthPx)
                                    player.seekToTimeline((totalMs * scrub).toLong())
                                    event.changes.forEach { it.consume() }
                                }
                            }
                            if (dragging) {
                                dragging = false
                                player.play()
                                playing = true
                            }
                        }
                    },
            ) {
                val usable = maxWidth - 2.dp
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
                )
                if (tz.isVisible(scrub)) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = usable * tz.toView(scrub) - 8.dp)
                            .size(16.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
                startFraction?.let {
                    if (tz.isVisible(it)) {
                        Box(
                            Modifier
                                .offset(x = usable * tz.toView(it))
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(Color(0xFF4CAF50)),
                        )
                    }
                }
                endFraction?.let {
                    if (tz.isVisible(it)) {
                        Box(
                            Modifier
                                .offset(x = usable * tz.toView(it))
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(Color(0xFFF44336)),
                        )
                    }
                }
            }
    }

    // Mini-map of the full buffer with the zoomed-in window highlighted, so it's obvious there's
    // more footage off-screen to either side. Only worth the space once zoomed.
    @Composable
    fun Minimap(modifier: Modifier) {
        if (tz.zoom > 1f) {
            BoxWithConstraints(modifier.height(6.dp)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
                )
                Box(
                    Modifier
                        .offset(x = maxWidth * tz.start)
                        .width(maxWidth * tz.span)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                )
            }
        }
    }

    @Composable
    fun InfoText(modifier: Modifier) {
        // Tenths of a second, not whole seconds — at 30fps a whole-second readout can't tell two
        // marks apart that are a dozen frames off, which is exactly the precision editing here needs.
        fun fmt(ms: Long) = "%.1f".format(ms / 1000f)
        val posMs = (totalMs * scrub).toLong()
        Text(
            buildString {
                append("${fmt(posMs)}초 / ${fmt(totalMs)}초  ·  ${fps}fps")
                startFraction?.let { append("   ·   시작 ${fmt((totalMs * it).toLong())}초") }
                endFraction?.let { append("   ·   끝 ${fmt((totalMs * it).toLong())}초") }
            },
            modifier = modifier,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // playback speed + single-frame step + toggles
    @Composable
    fun ChipsRow(modifier: Modifier, compact: Boolean = false) {
        FlowRow(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        ) {
            listOf(1f to "1배", 0.5f to "0.5배", 0.25f to "0.25배").forEach { (sp, label) ->
                ChoiceChip(label, sp == speed, compact) { speed = sp; player.setSpeed(sp) }
            }
            ChoiceChip("◀ 프레임", false, compact) { stepFrame(-1) }
            ChoiceChip("프레임 ▶", false, compact) { stepFrame(1) }

            if (startFraction != null && endFraction != null) {
                ChoiceChip(if (loopAb) "↻ A–B 반복 켜짐" else "↻ A–B 반복", loopAb, compact) {
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
                ChoiceChip("줌 초기화 ${"%.1f".format(viewScale)}×", false, compact) {
                    viewScale = 1f; viewOffsetX = 0f; viewOffsetY = 0f
                }
            }
            if (tz.zoom > 1f) {
                ChoiceChip("시간축 ${"%.1f".format(tz.zoom)}× 축소", false, compact) { tz = tz.reset() }
            }
        }
    }

    @Composable
    fun MarkerButtons(modifier: Modifier, compact: Boolean = false) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f).height(if (compact) 34.dp else ButtonDefaults.MinHeight),
                contentPadding = if (compact) PaddingValues(horizontal = 6.dp) else ButtonDefaults.ContentPadding,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    startFraction = markStart()
                },
            ) { Text("시작점", fontSize = if (compact) 12.sp else 14.sp) }
            OutlinedButton(
                modifier = Modifier.weight(1f).height(if (compact) 34.dp else ButtonDefaults.MinHeight),
                contentPadding = if (compact) PaddingValues(horizontal = 6.dp) else ButtonDefaults.ContentPadding,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    endFraction = markEnd()
                },
            ) { Text("끝점", fontSize = if (compact) 12.sp else 14.sp) }
        }
    }

    @Composable
    fun SaveButton(modifier: Modifier, compact: Boolean = false) {
        Button(
            modifier = modifier.height(if (compact) 34.dp else ButtonDefaults.MinHeight),
            contentPadding = if (compact) PaddingValues(horizontal = 6.dp) else ButtonDefaults.ContentPadding,
            enabled = startFraction != null && endFraction != null,
            onClick = {
                val a = startFraction ?: return@Button
                val b = endFraction ?: return@Button
                // Convert through the timeline so the cut lands on the real wall-clock instant
                // of that frame, not on "start + cumulative duration".
                val startMs = player.wallClockAtFraction(minOf(a, b))
                val endMs = player.wallClockAtFraction(maxOf(a, b))
                // Release this screen's own ExoPlayer *before* kicking off the export, not after
                // (onBackToLive's navigation would otherwise trigger it via onDispose a beat
                // later). Transformer builds its own ExoPlayer-based asset loader per segment and
                // switches between them — leaving this player's decoder mid-teardown at the same
                // moment likely contended for the same hardware decoder slot, which lines up with
                // the "Player release timed out" failures seen in the field even after a retry.
                runCatching { player.release() }
                service.exportManual(startMs, endMs, speed)
                onBackToLive()
            },
        ) {
            // The full slow-mo label wraps to 2 lines at the rail's 260dp width, which is exactly
            // what blows this button's height back up when it's supposed to be compact — a
            // shorter label in that mode instead of letting it wrap.
            val label = when {
                speed == 1f -> "구간 저장"
                compact -> "저장 ${speed}x"
                else -> "구간 저장 (${speed}배속 슬로모)"
            }
            Text(label, fontSize = if (compact) 12.sp else 14.sp, maxLines = 1)
        }
    }

    @Composable
    fun LiveButton(modifier: Modifier, compact: Boolean = false) {
        Button(
            modifier = modifier.height(if (compact) 34.dp else ButtonDefaults.MinHeight),
            contentPadding = if (compact) PaddingValues(horizontal = 6.dp) else ButtonDefaults.ContentPadding,
            onClick = onBackToLive,
        ) { Text("라이브로", fontSize = if (compact) 12.sp else 14.sp) }
    }

    RotatedEdge(orientation, Alignment.Center) {
        when {
            totalMs <= 0L -> Column(Modifier.fillMaxSize().padding(12.dp)) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("아직 녹화된 영상이 없어요 — 잠시 녹화되게 두고 다시 오세요.")
                }
                Button(modifier = Modifier.fillMaxWidth(), onClick = onBackToLive) { Text("라이브로 돌아가기") }
            }

            // Landscape: the physical screen is only as tall as its (portrait) width, so stacking
            // everything vertically the way portrait does would squeeze the video down to a
            // sliver under all the fixed-height controls. Put the video beside a narrow,
            // independently-scrolling control rail instead — the video gets the full height, and
            // there's plenty of width to spare for the rail.
            orientation.isLandscape -> Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VideoArea(Modifier.weight(1f).fillMaxHeight())
                Column(
                    Modifier
                        .width(260.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Track/Minimap/InfoText stay full-size (the scrub bar itself) — everything
                    // below is shrunk so "구간 저장"/"라이브로" don't scroll out of view under it.
                    Track(Modifier.fillMaxWidth())
                    Minimap(Modifier.fillMaxWidth())
                    InfoText(Modifier)
                    ChipsRow(Modifier.fillMaxWidth(), compact = true)
                    MarkerButtons(Modifier.fillMaxWidth(), compact = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SaveButton(Modifier.weight(2f), compact = true)
                        LiveButton(Modifier.weight(1f), compact = true)
                    }
                }
            }

            else -> Column(Modifier.fillMaxSize().padding(12.dp)) {
                VideoArea(Modifier.fillMaxWidth().weight(1f))
                Track(Modifier.fillMaxWidth())
                Minimap(Modifier.fillMaxWidth().padding(top = 3.dp))
                InfoText(Modifier)
                ChipsRow(Modifier.fillMaxWidth().padding(top = 8.dp))
                MarkerButtons(Modifier.fillMaxWidth().padding(top = 8.dp))
                SaveButton(Modifier.fillMaxWidth().padding(top = 8.dp))
                LiveButton(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}
