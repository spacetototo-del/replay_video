package com.diving.replay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import com.diving.replay.camera.RecordingService

enum class Screen { LIVE, REWIND, CLIPS, SETTINGS }

@UnstableApi
@Composable
fun AppRoot(service: RecordingService?) {
    var screen by remember { mutableStateOf(Screen.LIVE) }

    // Any sub-screen: system back returns to live instead of leaving the app.
    BackHandler(enabled = screen != Screen.LIVE) { screen = Screen.LIVE }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (service == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Starting buffer service…")
                }
                return@Surface
            }
            when (screen) {
                Screen.LIVE -> LivePreviewScreen(
                    service = service,
                    onEnterRewind = { screen = Screen.REWIND },
                    onOpenClips = { screen = Screen.CLIPS },
                    onOpenSettings = { screen = Screen.SETTINGS },
                )
                Screen.REWIND -> RewindScrubScreen(
                    service = service,
                    onBackToLive = { screen = Screen.LIVE },
                )
                Screen.CLIPS -> ClipListScreen(onBack = { screen = Screen.LIVE })
                Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.LIVE })
            }
        }
    }
}
