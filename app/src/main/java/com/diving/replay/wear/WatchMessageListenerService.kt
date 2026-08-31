package com.diving.replay.wear

import android.content.Intent
import android.util.Log
import com.diving.replay.Constants
import com.diving.replay.camera.RecordingService
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Phone-side receiver for the watch REC / STOP taps (plan §2, §4 Phase 7).
 *
 * Forwards to [RecordingService] (which owns the [MarkerManager]). REC also fires a local
 * broadcast the Activity's ScreenWakeController listens for (plan §11).
 *
 * NOTE: on API 34 a camera-type foreground service generally cannot be *started* from the
 * background. The Activity keeps the service alive while the app is open; if the phone screen is
 * fully off this hand-off may be refused by the OS — see 에러로그.md.
 */
class WatchMessageListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        Log.d(TAG, "watch message: ${event.path}")
        when (event.path) {
            Constants.PATH_MARK_START -> {
                sendBroadcast(Intent(ACTION_WAKE_SCREEN).setPackage(packageName))
                forward(ACTION_MARK_START)
            }
            Constants.PATH_MARK_END -> forward(ACTION_MARK_END)
        }
    }

    /**
     * Prefer handing the marker straight to the running service. `startForegroundService` is
     * only the fallback for when it isn't running, and API 34 can refuse that outright for a
     * camera-type service while the app is backgrounded — which is precisely when the watch is
     * being used. If both paths fail the watch is told, rather than left showing "Saving…".
     */
    private fun forward(action: String) {
        RecordingService.instance?.let { service ->
            when (action) {
                ACTION_MARK_START -> service.onMarkStart()
                ACTION_MARK_END -> service.onMarkEnd()
            }
            return
        }
        try {
            startForegroundService(Intent(this, RecordingService::class.java).setAction(action))
        } catch (e: Exception) {
            Log.e(TAG, "could not deliver $action to RecordingService", e)
            WatchAck(applicationContext).send(WatchAck.Kind.ERROR, "폰 앱을 먼저 여세요")
        }
    }

    companion object {
        private const val TAG = "WatchListener"
        const val ACTION_WAKE_SCREEN = "com.diving.replay.WAKE_SCREEN"
        const val ACTION_MARK_START = "com.diving.replay.MARK_START"
        const val ACTION_MARK_END = "com.diving.replay.MARK_END"
    }
}
