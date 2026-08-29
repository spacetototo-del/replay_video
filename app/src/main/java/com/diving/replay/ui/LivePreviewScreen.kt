package com.diving.replay.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import com.diving.replay.camera.ClipExporter
import com.diving.replay.camera.RecordingService
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Always-on live preview (plan §0.1, §4 Phase 2). One-finger swipe down enters the rewind
 * scrubber; two-finger pinch zooms the camera (which also zooms the recorded buffer). The zoom
 * can be locked so it can't drift mid-session — unlocking needs a deliberate 2-second hold.
 * The REC/STOP row mirrors the watch so the app is fully usable without it (plan §8).
 */
@UnstableApi
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LivePreviewScreen(
    service: RecordingService,
    onEnterRewind: () -> Unit,
    onOpenClips: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by service.state.collectAsState()
    val armedFrom by service.markerStart.collectAsState()
    val lastExport by service.lastExport.collectAsState()
    val segments by service.segments.collectAsState()
    val zoom by service.zoom.collectAsState()

    var zoomLocked by remember { mutableStateOf(false) }
    val coverageSec = remember(segments) { (service.bufferCoverageMs() / 1000).toInt() }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(zoomLocked) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var multiTouch = false
                    var totalDy = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2) {
                            multiTouch = true
                            if (!zoomLocked) {
                                val zc = event.calculateZoom()
                                if (zc != 1f) {
                                    service.setZoomRatio(service.zoom.value.ratio * zc)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } else if (!multiTouch) {
                            totalDy += event.changes.firstOrNull()?.positionChange()?.y ?: 0f
                        }
                    }
                    if (!multiTouch && totalDy > 60f) onEnterRewind()
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.PERFORMANCE }
            },
            update = { view -> service.attachPreview(view.surfaceProvider) },
        )
        DisposableEffect(Unit) { onDispose { service.detachPreview() } }

        // top-left overlay: status strip, then the "saved" toast stacked under it (no overlap)
        Column(
            Modifier.align(Alignment.TopStart).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    buildString {
                        append(com.diving.replay.Constants.VERSION_LABEL)
                        append("  ·  ")
                        append(
                            when (state) {
                                RecordingService.State.STARTING -> "starting…"
                                RecordingService.State.BUFFERING -> "buffering ${coverageSec}s"
                                RecordingService.State.EXPORTING -> "saving clip…"
                                RecordingService.State.ERROR -> "buffer error"
                            },
                        )
                        if (armedFrom != null) append("  •  ● REC armed")
                    },
                    color = Color.White,
                )
            }

            (lastExport as? ClipExporter.Result.Saved)?.let {
                val speedTag = if (it.speed != 1f) " ${it.speed}x" else ""
                Text(
                    text = if (it.partial) {
                        "saved${speedTag} (partial, ${it.savedDurationMs / 1000}s)"
                    } else {
                        "saved${speedTag} ${it.savedDurationMs / 1000}s → gallery"
                    },
                    color = Color.White,
                    modifier = Modifier
                        .background(Color(0xCC00AA00), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        // zoom + lock control
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .background(Color(0x99000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .pointerInput(zoomLocked) {
                    awaitEachGesture {
                        awaitFirstDown()
                        if (zoomLocked) {
                            // must hold the full 2s to unlock
                            val released = withTimeoutOrNull(2_000L) { waitForUpOrCancellation() }
                            if (released == null) zoomLocked = false
                        } else {
                            if (waitForUpOrCancellation() != null) zoomLocked = true
                        }
                    }
                },
        ) {
            Text(
                text = buildString {
                    append(if (zoomLocked) "🔒 " else "🔓 ")
                    append(String.format("%.1f×", zoom.ratio))
                    if (zoomLocked) append("  hold to unlock")
                },
                color = Color.White,
            )
        }

        // FlowRow so the controls wrap to a second line on the narrow Fold cover screen
        // instead of overflowing off-screen.
        FlowRow(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val compact = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            if (armedFrom == null) {
                Button(
                    onClick = { service.onMarkStart() },
                    contentPadding = compact,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                ) { Text("REC") }
            } else {
                Button(onClick = { service.onMarkEnd() }, contentPadding = compact) { Text("STOP") }
            }
            Button(onClick = onEnterRewind, contentPadding = compact) { Text("Rewind") }
            Button(onClick = onOpenClips, contentPadding = compact) { Text("Clips") }
            Button(onClick = onOpenSettings, contentPadding = compact) { Text("Settings") }
        }
    }
}
