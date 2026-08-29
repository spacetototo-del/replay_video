package com.diving.replay.power

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.diving.replay.Constants
import com.diving.replay.data.IdleScreenMode
import com.diving.replay.wear.WatchMessageListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Touch-activity-driven screen dimming for poolside use (plan §11).
 *
 * The camera keeps recording no matter what — this only touches the display:
 *  - Any screen touch (via [onUserInteraction]) or a watch REC signal ([wake]) snaps the screen
 *    to full brightness and starts a [Constants.SCREEN_IDLE_DIM_MS] countdown.
 *  - When the countdown expires with no further touch, the screen settles per [mode]:
 *      [IdleScreenMode.ALWAYS_BRIGHT] stays at the user's brightness,
 *      [IdleScreenMode.DIM] drops to a dim floor,
 *      [IdleScreenMode.SCREEN_OFF] lets the screen time out.
 *
 * Attach from the Activity's lifecycle and forward Activity.onUserInteraction().
 */
class ScreenWakeController(
    private val activity: Activity,
    private val scope: CoroutineScope,
) {
    /** Updated from the Activity as the Settings value changes. */
    var mode: IdleScreenMode = IdleScreenMode.DIM
        set(value) {
            field = value
            if (started) bumpAwake()
        }

    private var started = false
    private var redimJob: Job? = null

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WatchMessageListenerService.ACTION_WAKE_SCREEN) wake()
        }
    }

    fun onStart() {
        started = true
        ContextCompat.registerReceiver(
            activity,
            wakeReceiver,
            IntentFilter(WatchMessageListenerService.ACTION_WAKE_SCREEN),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        bumpAwake()
    }

    fun onStop() {
        started = false
        redimJob?.cancel()
        runCatching { activity.unregisterReceiver(wakeReceiver) }
    }

    /** A watch REC signal — treat like a touch. */
    fun wake() = bumpAwake()

    /** Forward from Activity.onUserInteraction(): any touch keeps the screen awake. */
    fun onUserInteraction() {
        if (started) bumpAwake()
    }

    /** Full brightness now, then settle back to the idle mode after the timeout. */
    private fun bumpAwake() {
        redimJob?.cancel()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (mode == IdleScreenMode.SCREEN_OFF) nudgeScreenOn()
        setBrightness(if (mode == IdleScreenMode.ALWAYS_BRIGHT) BRIGHTNESS_NONE else BRIGHTNESS_FULL)
        if (mode == IdleScreenMode.ALWAYS_BRIGHT) return
        redimJob = scope.launch {
            delay(Constants.SCREEN_IDLE_DIM_MS)
            settleIdle()
        }
    }

    /** Timeout elapsed with no touch — settle the display per the current mode. */
    private fun settleIdle() {
        when (mode) {
            IdleScreenMode.ALWAYS_BRIGHT -> {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setBrightness(BRIGHTNESS_NONE)
            }
            IdleScreenMode.DIM -> {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setBrightness(BRIGHTNESS_DIM)
            }
            IdleScreenMode.SCREEN_OFF -> {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setBrightness(BRIGHTNESS_NONE)
            }
        }
    }

    /** Briefly grab a wake lock so a timed-out screen lights back up when REC arrives. */
    private fun nudgeScreenOn() {
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val lock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "DivingReplay:wake",
        )
        runCatching { lock.acquire(3_000L) }
    }

    private fun setBrightness(value: Float) {
        val lp = activity.window.attributes
        lp.screenBrightness = value
        activity.window.attributes = lp
    }

    companion object {
        /** Lowest non-off brightness; tune during real-world battery testing (plan §11). */
        const val BRIGHTNESS_DIM = 0.03f
        const val BRIGHTNESS_FULL = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        const val BRIGHTNESS_NONE = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
}
