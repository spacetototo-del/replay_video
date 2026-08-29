package com.diving.replay.camera

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.diving.replay.Constants
import com.diving.replay.DivingReplayApp
import com.diving.replay.R
import com.diving.replay.data.CaptureSettings
import com.diving.replay.data.CaptureSettingsRepository
import com.diving.replay.data.TargetResolution
import com.diving.replay.util.awaitCompat
import com.diving.replay.wear.WatchAck
import com.diving.replay.wear.WatchMessageListenerService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Always-on foreground service that keeps the last few minutes of footage on disk as a ring of
 * short segments (plan §2, §4 Phase 1). Never stops the camera; REC/STOP from the watch only drop
 * markers that [ClipExporter] later cuts against (plan §8).
 */
@UnstableApi
class RecordingService : LifecycleService() {

    enum class State { STARTING, BUFFERING, EXPORTING, ERROR }

    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    private val binder = LocalBinder()

    private lateinit var settingsRepo: CaptureSettingsRepository
    private lateinit var rotation: SegmentRotationManager
    val markers = MarkerManager()
    private lateinit var exporter: ClipExporter
    private lateinit var watchAck: WatchAck

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private val preview = Preview.Builder().build()
    private var activeRecording: Recording? = null
    private var segmentLoop: Job? = null
    private var exporting = false

    private val _state = MutableStateFlow(State.STARTING)
    val state: StateFlow<State> = _state.asStateFlow()

    val segments get() = rotation.segments
    val markerStart get() = markers.start

    private val _lastExport = MutableStateFlow<ClipExporter.Result?>(null)
    val lastExport: StateFlow<ClipExporter.Result?> = _lastExport.asStateFlow()

    /** Digital zoom applied to the whole camera stream — preview AND the recorded buffer. */
    data class ZoomInfo(val ratio: Float = 1f, val minRatio: Float = 1f, val maxRatio: Float = 1f)
    private val _zoom = MutableStateFlow(ZoomInfo())
    val zoom: StateFlow<ZoomInfo> = _zoom.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        settingsRepo = CaptureSettingsRepository(applicationContext)
        rotation = SegmentRotationManager(File(filesDir, Constants.SEGMENT_DIR))
        exporter = ClipExporter(applicationContext, rotation)
        watchAck = WatchAck(applicationContext)
        startForegroundCompat()
        observeSettings()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (segmentLoop == null && hasCameraPermission()) startBuffering()
        when (intent?.action) {
            WatchMessageListenerService.ACTION_MARK_START -> onMarkStart()
            WatchMessageListenerService.ACTION_MARK_END -> onMarkEnd()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    // ---- preview wiring for the Activity ----

    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) = preview.setSurfaceProvider(surfaceProvider)
    fun detachPreview() = preview.setSurfaceProvider(null)

    /** Pinch-to-zoom from the live screen. Clamped to the sensor's supported range. */
    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        val z = _zoom.value
        cam.cameraControl.setZoomRatio(ratio.coerceIn(z.minRatio, z.maxRatio))
    }

    fun bufferCoverageMs(): Long = rotation.coverageMs()

    // ---- settings: rebind + wipe buffer when the encoder shape changes (plan §4 Phase 6) ----

    private fun observeSettings() {
        // Buffer length: apply live, no rebind needed.
        lifecycleScope.launch {
            settingsRepo.settings
                .map { it.bufferRetentionMs }
                .distinctUntilChanged()
                .collect { rotation.retentionMs = it }
        }
        // Resolution: needs a camera rebind + buffer wipe (can't concat mixed resolutions).
        // Bitrate changes ride along on the next rebind rather than thrashing mid slider-drag.
        lifecycleScope.launch {
            settingsRepo.settings
                .map { it.resolution }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (hasCameraPermission()) restartBuffering() }
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun restartBuffering() {
        segmentLoop?.cancel()
        activeRecording?.stop()
        activeRecording = null
        camera = null
        rotation.clear() // segments at a different resolution can't be concatenated
        segmentLoop = null
        startBuffering()
    }

    // ---- buffering loop ----

    @RequiresPermission(Manifest.permission.CAMERA)
    private fun startBuffering() {
        segmentLoop = lifecycleScope.launch {
            try {
                val settings = settingsRepo.settings.first()
                rotation.retentionMs = settings.bufferRetentionMs
                bindCamera(settings)
                _state.value = State.BUFFERING
                var consecutiveFailures = 0
                while (isActive) {
                    try {
                        recordOneSegment()
                        consecutiveFailures = 0
                    } catch (e: IllegalStateException) {
                        // Recorder not ready yet between segments — back off briefly and retry.
                        Log.w(TAG, "segment start retry (${++consecutiveFailures})", e)
                        if (consecutiveFailures >= 5) throw e
                        delay(300)
                    }
                    rotation.prune()
                }
            } catch (e: Exception) {
                Log.e(TAG, "buffering loop failed", e)
                _state.value = State.ERROR
            }
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun bindCamera(settings: CaptureSettings) {
        val provider = ProcessCameraProvider.getInstance(this).awaitCompat()
        cameraProvider = provider
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(settings.resolution.toQuality()))
            .setTargetVideoEncodingBitRate(settings.bitRateBps)
            .build()
        val capture = VideoCapture.withOutput(recorder)
        videoCapture = capture
        provider.unbindAll()
        camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        camera?.cameraInfo?.zoomState?.observe(this) { zs ->
            _zoom.value = ZoomInfo(zs.zoomRatio, zs.minZoomRatio, zs.maxZoomRatio)
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun recordOneSegment() {
        val capture = videoCapture ?: return
        val startedAt = System.currentTimeMillis()
        val file = rotation.newSegmentFile(startedAt)
        val finalized = CompletableDeferred<Long>()

        val pending = capture.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
        if (hasAudioPermission()) pending.withAudioEnabled() // plan §7: RECORD_AUDIO optional

        val recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
                if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) {
                    Log.w(TAG, "segment finalize error ${event.error}")
                    file.delete()
                    finalized.complete(0)
                } else {
                    rotation.register(file, startedAt, durationMs)
                    finalized.complete(durationMs)
                }
            }
        }
        activeRecording = recording

        delay(Constants.SEGMENT_DURATION_MS)
        recording.stop()
        finalized.await()
        activeRecording = null
    }

    // ---- markers / export ----

    fun onMarkStart(atMs: Long = System.currentTimeMillis()) {
        markers.markStart(atMs)
        updateNotification()
    }

    fun onMarkEnd(atMs: Long = System.currentTimeMillis()) {
        val window = markers.markEnd(atMs)
        updateNotification()
        if (window == null) {
            watchAck.send(WatchAck.Kind.ERROR, "STOP without REC")
            return
        }
        runExport(window.first, window.second, notifyWatch = true)
    }

    /** Manual export from the rewind screen (plan §4 Phase 2, "save from here"). */
    fun exportManual(startMs: Long, endMs: Long) = runExport(startMs, endMs, notifyWatch = false)

    private fun runExport(startMs: Long, endMs: Long, notifyWatch: Boolean) {
        if (exporting) {
            if (notifyWatch) watchAck.send(WatchAck.Kind.ERROR, "export busy")
            return
        }
        exporting = true
        lifecycleScope.launch {
            _state.value = State.EXPORTING
            updateNotification()
            val result = exporter.export(startMs, endMs)
            _lastExport.value = result
            if (notifyWatch) {
                when (result) {
                    is ClipExporter.Result.Saved ->
                        watchAck.send(
                            if (result.partial) WatchAck.Kind.PARTIAL else WatchAck.Kind.SAVED,
                            "${result.savedDurationMs / 1000}s",
                        )
                    is ClipExporter.Result.Failed -> watchAck.send(WatchAck.Kind.ERROR, result.reason)
                    ClipExporter.Result.NothingToSave -> watchAck.send(WatchAck.Kind.ERROR, "nothing buffered")
                }
            }
            exporting = false
            _state.value = if (segmentLoop?.isActive == true) State.BUFFERING else State.ERROR
            updateNotification()
        }
    }

    override fun onDestroy() {
        segmentLoop?.cancel()
        activeRecording?.stop()
        cameraProvider?.unbindAll()
        super.onDestroy()
    }

    // ---- misc ----

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
            ?.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val text = when (_state.value) {
            State.EXPORTING -> "Saving clip…"
            State.ERROR -> "Buffer stopped — reopen the app"
            else -> if (markers.isArmed) "REC armed — buffering" else getString(R.string.notif_recording_text)
        }
        return NotificationCompat.Builder(this, DivingReplayApp.CHANNEL_RECORDING)
            .setContentTitle(getString(R.string.notif_recording_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

private fun TargetResolution.toQuality(): Quality = when (this) {
    TargetResolution.HD_720P -> Quality.HD
    TargetResolution.FHD_1080P -> Quality.FHD
    TargetResolution.UHD_2160P -> Quality.UHD
}
