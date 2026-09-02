package com.diving.replay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import com.diving.replay.camera.RecordingService

enum class Screen { LIVE, REWIND, DELAYED, CLIPS, SETTINGS }

/**
 * @param onHoldScreenAwake raised while a screen is being *watched* rather than operated, so the
 *   idle dimmer doesn't darken a replay nobody is touching.
 * @param onExitApp stop the buffer service and close the app (Settings → 종료).
 */
@UnstableApi
@Composable
fun AppRoot(
    service: RecordingService?,
    onHoldScreenAwake: (Boolean) -> Unit = {},
    onExitApp: () -> Unit = {},
) {
    var screen by remember { mutableStateOf(Screen.LIVE) }

    // Any sub-screen: system back returns to live instead of leaving the app.
    BackHandler(enabled = screen != Screen.LIVE) { screen = Screen.LIVE }

    // Rewind and delayed replay are watched hands-off; everything else follows the normal
    // touch-driven dim timer.
    LaunchedEffect(screen) {
        onHoldScreenAwake(screen == Screen.REWIND || screen == Screen.DELAYED)
    }

    DivingTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Note: no early `return@Surface` here. Returning out of a composable lambda
            // corrupts Compose's slot table — it crashed the rewind screen once already.
            if (service == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("버퍼 서비스 시작 중…", color = MaterialTheme.colorScheme.onBackground)
                }
            } else {
                when (screen) {
                    Screen.LIVE -> LivePreviewScreen(
                        service = service,
                        onEnterRewind = { screen = Screen.REWIND },
                        onEnterDelayed = { screen = Screen.DELAYED },
                        onOpenClips = { screen = Screen.CLIPS },
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )
                    Screen.REWIND -> RewindScrubScreen(
                        service = service,
                        onBackToLive = { screen = Screen.LIVE },
                    )
                    Screen.DELAYED -> DelayedReplayScreen(
                        service = service,
                        onBackToLive = { screen = Screen.LIVE },
                    )
                    Screen.CLIPS -> ClipListScreen(onBack = { screen = Screen.LIVE })
                    Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.LIVE }, onExitApp = onExitApp)
                }
            }
        }
    }
}
