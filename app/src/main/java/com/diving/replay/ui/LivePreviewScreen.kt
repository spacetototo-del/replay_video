package com.diving.replay.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import com.diving.replay.camera.ClipExporter
import com.diving.replay.camera.RecordingService
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Always-on live preview (plan §0.1, §4 Phase 2). One-finger swipe down enters the rewind
 * scrubber; two-finger pinch zooms the camera (which also zooms the recorded buffer). The zoom
 * can be locked so it can't drift mid-session — unlocking needs a deliberate 2-second hold.
 * The record row mirrors the watch so the app is fully usable without it (plan §8).
 */
@UnstableApi
@Composable
fun LivePreviewScreen(
    service: RecordingService,
    onEnterRewind: () -> Unit,
    onEnterDelayed: () -> Unit,
    onOpenClips: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by service.state.collectAsState()
    val armedFrom by service.markerStart.collectAsState()
    val lastExport by service.lastExport.collectAsState()
    val segments by service.segments.collectAsState()
    val zoom by service.zoom.collectAsState()
    val audioEnabled by service.audioEnabled.collectAsState()
    val overheating by service.overheating.collectAsState()

    val haptics = LocalHapticFeedback.current
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
                    // Anchor the pinch to the ratio at gesture start and accumulate locally.
                    // Reading service.zoom.value each event chases a value that only updates a
                    // frame later, which is what made the zoom feel laggy and steppy.
                    val pinchBase = service.zoom.value.ratio
                    var pinchAccum = 1f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2) {
                            multiTouch = true
                            if (!zoomLocked) {
                                val zc = event.calculateZoom()
                                if (zc != 1f) {
                                    pinchAccum *= zc
                                    service.setZoomRatio(pinchBase * pinchAccum)
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
            // Attach in factory only — running it from `update` re-set the surface provider on
            // every recomposition (and the zoom state recomposes this screen per frame),
            // which hitched the preview during a pinch.
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    service.attachPreview(surfaceProvider)
                }
            },
        )
        DisposableEffect(Unit) { onDispose { service.detachPreview() } }

        // top-left: version + status, then warnings and the "saved" toast stacked underneath
        Column(
            Modifier.align(Alignment.TopStart).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val statusText = buildString {
                append(com.diving.replay.Constants.VERSION_LABEL)
                append("  ·  ")
                append(
                    when (state) {
                        RecordingService.State.STARTING -> "시작 중…"
                        RecordingService.State.BUFFERING -> "버퍼링 ${coverageSec}초"
                        RecordingService.State.EXPORTING -> "저장 중…"
                        RecordingService.State.ERROR -> "버퍼 오류"
                    },
                )
                if (armedFrom != null) append("  •  ● 녹화 중")
            }
            StatusPill(statusText, color = MaterialTheme.colorScheme.onSurface)

            if (!audioEnabled) {
                StatusPillTinted("🔇 마이크 권한 없음 — 소리 없이 녹화됩니다", MaterialTheme.colorScheme.error)
            }
            if (overheating) {
                StatusPillTinted("🌡 기기 과열 — 프레임이 끊길 수 있어요. 그늘로 옮기거나 fps/해상도를 낮추세요", DivingTokens.warn, dark = true)
            }
            (lastExport as? ClipExporter.Result.Saved)?.let {
                val speedTag = if (it.speed != 1f) " ${it.speed}x" else ""
                val text = if (it.partial) {
                    "${it.savedDurationMs / 1000}초 저장됨$speedTag (일부만)"
                } else {
                    "${it.savedDurationMs / 1000}초 저장됨$speedTag → 갤러리"
                }
                StatusPillTinted(text, DivingTokens.ok, dark = true)
            }
        }

        // top-right: zoom + lock
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .pointerInput(zoomLocked) {
                    awaitEachGesture {
                        awaitFirstDown()
                        if (zoomLocked) {
                            val released = withTimeoutOrNull(2_000L) { waitForUpOrCancellation() }
                            if (released == null) zoomLocked = false
                        } else {
                            if (waitForUpOrCancellation() != null) zoomLocked = true
                        }
                    }
                },
        ) {
            StatusPill(
                buildString {
                    append(if (zoomLocked) "🔒 " else "🔓 ")
                    append(String.format("%.1f×", zoom.ratio))
                    if (zoomLocked) append("  길게 눌러 해제")
                },
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // bottom: prominent record button + an evenly-shared action bar. Width-capped and
        // centred so it never clusters or stretches on a tablet / unfolded Fold, and each
        // action takes an equal weight so it stays balanced on the narrow Fold cover screen.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .widthIn(max = DivingTokens.contentMaxWidth)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (armedFrom == null) {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        service.onMarkStart()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(DivingTokens.chipRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DivingTokens.recRed,
                        contentColor = androidx.compose.ui.graphics.Color.White,
                    ),
                ) {
                    Icon(Icons.Rounded.FiberManualRecord, contentDescription = null)
                    Text("  녹화 시작", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
            } else {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        service.onMarkEnd()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(DivingTokens.chipRadius),
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null)
                    Text("  정지", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BarAction(Icons.Rounded.ContentCut, "되감기·구간저장", onEnterRewind, Modifier.weight(1f))
                BarAction(Icons.Rounded.HistoryToggleOff, "지연재생", onEnterDelayed, Modifier.weight(1f))
                BarAction(Icons.Rounded.VideoLibrary, "저장영상", onOpenClips, Modifier.weight(1f))
                BarAction(Icons.Rounded.Settings, "설정", onOpenSettings, Modifier.weight(1f))
            }
        }
    }
}
