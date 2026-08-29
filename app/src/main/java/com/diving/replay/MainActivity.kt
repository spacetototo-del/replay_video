package com.diving.replay

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.diving.replay.camera.RecordingService
import com.diving.replay.data.CaptureSettingsRepository
import com.diving.replay.power.ScreenWakeController
import com.diving.replay.ui.AppRoot
import kotlinx.coroutines.launch

/**
 * Single-activity host. Owns: runtime permissions, the bound [RecordingService], and the
 * dim/wake controller (plan §11). UI lives in [AppRoot].
 *
 * We ask for CAMERA + RECORD_AUDIO (+ POST_NOTIFICATIONS on T+) up front and re-ask on every
 * start if any is still missing — otherwise a build that already had CAMERA from a prior install
 * would never prompt for RECORD_AUDIO and the buffer would record silently. The service is only
 * created once CAMERA is granted so its camera-type foreground service starts cleanly on API 34.
 */
@UnstableApi
class MainActivity : ComponentActivity() {

    private var service by mutableStateOf<RecordingService?>(null)
    private var bound = false
    private lateinit var wake: ScreenWakeController

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as RecordingService.LocalBinder).service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (hasCamera() && !bound) startAndBindService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wake = ScreenWakeController(this, lifecycleScope)
        val settingsRepo = CaptureSettingsRepository(applicationContext)
        lifecycleScope.launch {
            settingsRepo.settings.collect { wake.mode = it.idleScreen }
        }
        setContent { AppRoot(service = service) }

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startAndBindService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        wake.onStart()
        if (hasCamera() && !bound) startAndBindService()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        wake.onUserInteraction()
    }

    override fun onStop() {
        wake.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    private fun startAndBindService() {
        RecordingService.start(this)
        bound = bindService(
            Intent(this, RecordingService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
