package com.diving.replay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class DivingReplayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createRecordingChannel()
    }

    private fun createRecordingChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_RECORDING,
                getString(R.string.notif_channel_recording),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
    }
}
