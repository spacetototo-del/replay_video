package com.diving.replay.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
    // Activity is portrait-locked; the overlay controls dock to the physical bottom/top edge and
    // turn to follow the phone. Purely visual — the camera and the recorded file are unaffected.
    val orientation = rememberUprightOrientation()

    @Composable
    fun RecordStopButton(armed: Boolean, compact: Boolean, modifier: Modifier = Modifier) {
        if (!armed) {
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    service.onMarkStart()
                },
                modifier = modifier.height(if (compact) 44.dp else 52.dp),
                shape = RoundedCornerShape(DivingTokens.chipRadius),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DivingTokens.recRed,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                ),
            ) {
                Icon(Icons.Rounded.FiberManualRecord, contentDescription = null)
                Text(if (compact) " REC" else "  녹화 시작", fontWeight = FontWeight.SemiBold, fontSize = if (compact) 14.sp else 16.sp)
            }
        } else {
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    service.onMarkEnd()
                },
                modifier = modifier.height(if (compact) 44.dp else 52.dp),
                shape = RoundedCornerShape(DivingTokens.chipRadius),
            ) {
                Icon(Icons.Rounded.Stop, contentDescription = null)
                Text(if (compact) " 정지" else "  정지", fontWeight = FontWeight.SemiBold, fontSize = if (compact) 14.sp else 16.sp)
            }
        }
    }

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
                    // FIT_CENTER (not the default FILL_CENTER): show the whole ViewPort-cropped
                    // frame the encoder receives, letterboxed, so the live image is exactly what
                    // gets recorded. FILL_CENTER would crop the sides back off on tall screens.
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    service.attachPreview(surfaceProvider)
                }
            },
        )
        DisposableEffect(Unit) { onDispose { service.detachPreview() } }

        // top-left: version + status, then warnings and the "saved" toast stacked underneath
        RotatedEdge(orientation, Alignment.TopStart) {
        Column(
            Modifier.padding(12.dp),
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
            when (val ex = lastExport) {
                is ClipExporter.Result.Saved -> {
                    val speedTag = if (ex.speed != 1f) " ${ex.speed}x" else ""
                    val text = if (ex.partial) {
                        "${ex.savedDurationMs / 1000}초 저장됨$speedTag (앞부분 일부 잘림)"
                    } else {
                        "${ex.savedDurationMs / 1000}초 저장됨$speedTag → 갤러리"
                    }
                    StatusPillTinted(text, DivingTokens.ok, dark = true)
                }
                is ClipExporter.Result.Failed ->
                    StatusPillTinted("저장 실패 — 다시 시도하세요", MaterialTheme.colorScheme.error)
                ClipExporter.Result.NothingToSave ->
                    StatusPillTinted("구간을 저장하지 못했어요 — 너무 짧거나 버퍼 범위를 벗어났어요", MaterialTheme.colorScheme.error)
                null -> Unit
            }
        }
        }

        // top-right: zoom + lock — docks to the physical top edge and turns with the phone
        RotatedEdge(orientation, Alignment.TopEnd) {
        Box(
            Modifier
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
        }

        // bottom: record button + an evenly-shared action bar. RotatedEdge docks it to the
        // physical bottom edge and turns it with the phone, so it slides round to the new
        // bottom in landscape rather than staying stuck along the portrait edge.
        RotatedEdge(orientation, Alignment.BottomCenter) {
        BoxWithConstraints {
            // Cap explicitly against the measured width here, rather than trusting
            // fillMaxWidth().widthIn(max=...) to shrink on its own — that combination silently
            // failed to cap inside RotatedEdge's rotated frame (measured ~660dp wide on a real
            // device instead of the intended 440dp cap), which is what let this bar balloon.
            val barWidth = minOf(maxWidth, 440.dp)
            if (orientation.isLandscape) {
                // This dock's cross-axis budget in landscape is the phone's own portrait
                // *width* (~360dp on a normal phone vs ~670dp on an unfolded Fold) — the
                // two-row portrait layout below is a small slice of that on a Fold but nearly
                // half of it on a regular phone. One compact icon row instead.
                Row(
                    Modifier
                        .width(barWidth)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RecordStopButton(armed = armedFrom != null, compact = true, modifier = Modifier.weight(1.6f))
                    BarAction(Icons.Rounded.ContentCut, "되감기·편집", onEnterRewind, Modifier.weight(1f), compact = true)
                    BarAction(Icons.Rounded.HistoryToggleOff, "지연재생", onEnterDelayed, Modifier.weight(1f), compact = true)
                    BarAction(Icons.Rounded.VideoLibrary, "저장영상", onOpenClips, Modifier.weight(1f), compact = true)
                    BarAction(Icons.Rounded.Settings, "설정", onOpenSettings, Modifier.weight(1f), compact = true)
                }
            } else {
                Column(
                    Modifier
                        .width(barWidth)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RecordStopButton(armed = armedFrom != null, compact = false, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BarAction(Icons.Rounded.ContentCut, "되감기·편집", onEnterRewind, Modifier.weight(1f))
                        BarAction(Icons.Rounded.HistoryToggleOff, "지연재생", onEnterDelayed, Modifier.weight(1f))
                        BarAction(Icons.Rounded.VideoLibrary, "저장영상", onOpenClips, Modifier.weight(1f))
                        BarAction(Icons.Rounded.Settings, "설정", onOpenSettings, Modifier.weight(1f))
                    }
                }
            }
        }
        }
    }
}
