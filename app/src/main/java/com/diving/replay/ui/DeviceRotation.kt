package com.diving.replay.ui

import android.view.OrientationEventListener
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * The activity is pinned to portrait (recorded files must stay portrait-safe — see the
 * screenOrientation lock added in v7), so the system never turns the UI when the phone is held
 * sideways. This reads the accelerometer directly and returns how far the *controls* should be
 * counter-rotated to sit upright in the user's hands: 0, 90, 180 or 270 degrees, animated.
 *
 * It only affects how the buttons are drawn — the camera target rotation and the recorded video
 * are untouched, so turning the phone never flips the footage.
 *
 * A 20° dead-zone between the four buckets stops it chattering when the phone is held near 45°.
 */
@Composable
fun rememberUprightRotation(): Float {
    val context = LocalContext.current
    var rotation by remember { mutableFloatStateOf(0f) } // accumulates along the shortest arc
    var quarter by remember { mutableIntStateOf(0) }      // 0..3, current snapped orientation

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                val next = when {
                    deg >= 325 || deg <= 35 -> 0   // upright
                    deg in 55..125 -> 3           // phone turned right → rotate content 270°
                    deg in 145..215 -> 2          // upside down
                    deg in 235..305 -> 1          // phone turned left → rotate content 90°
                    else -> return               // in a dead-zone: keep the current orientation
                }
                if (next == quarter) return
                var delta = (next - quarter) * 90f
                if (delta > 180f) delta -= 360f
                if (delta < -180f) delta += 360f
                rotation += delta
                quarter = next
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    val animated by animateFloatAsState(targetValue = rotation, label = "uprightRotation")
    return animated
}
