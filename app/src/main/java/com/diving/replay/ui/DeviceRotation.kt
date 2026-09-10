package com.diving.replay.ui

import android.view.OrientationEventListener
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.lerp

/**
 * How the phone is being held, for a screen that's locked to portrait (recorded files must stay
 * portrait-safe — see the screenOrientation lock from v7). The system never turns the UI, so the
 * overlay controls turn themselves.
 *
 * @property degrees animated content rotation, shortest-arc, so a turn animates smoothly.
 * @property quarter 0 upright · 1 turned one way · 2 upside down · 3 turned the other way.
 */
data class UprightOrientation(val degrees: Float, val quarter: Int) {
    val isLandscape get() = quarter == 1 || quarter == 3
}

/**
 * Reads [OrientationEventListener] directly (independent of the locked window) and reports the
 * snapped orientation with a 20° dead-zone between buckets so it doesn't chatter near 45°.
 */
@Composable
fun rememberUprightOrientation(): UprightOrientation {
    val context = LocalContext.current
    var degrees by remember { mutableFloatStateOf(0f) } // accumulates along the shortest arc
    var quarter by remember { mutableIntStateOf(0) }

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                val next = when {
                    deg >= 325 || deg <= 35 -> 0
                    deg in 55..125 -> 1
                    deg in 145..215 -> 2
                    deg in 235..305 -> 3
                    else -> return // dead-zone: hold the current orientation
                }
                if (next == quarter) return
                var delta = (next - quarter) * 90f
                if (delta > 180f) delta -= 360f
                if (delta < -180f) delta += 360f
                // content spins opposite the device
                degrees -= delta
                quarter = next
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    val animated by animateFloatAsState(targetValue = degrees, label = "uprightRotation")
    return UprightOrientation(animated, quarter)
}

/**
 * Docks [content] to one edge of the screen and turns it to follow the phone. The child is laid
 * out in a frame the size of the *physical* screen (width/height swapped as it turns), so
 * `align` picks the physical edge and the child fills the physical width there — the control bar
 * slides round to the new bottom instead of just spinning in place.
 *
 * @param align edge in the child's own upright frame — [Alignment.BottomCenter] for the action
 *   bar, [Alignment.TopStart]/[Alignment.TopEnd] for status chips.
 */
@Composable
fun RotatedEdge(
    orientation: UprightOrientation,
    align: Alignment,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val a = ((orientation.degrees % 180f) + 180f) % 180f
        val land = if (a <= 90f) a / 90f else (180f - a) / 90f // 0 upright, 1 sideways
        val frameW = lerp(maxWidth, maxHeight, land)
        val frameH = lerp(maxHeight, maxWidth, land)
        Box(
            Modifier
                .align(Alignment.Center)
                .requiredSize(frameW, frameH)
                .rotate(orientation.degrees),
            contentAlignment = align,
            content = content,
        )
    }
}
