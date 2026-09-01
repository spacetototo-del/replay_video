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
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.util.Rational
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
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
import kotlinx.coroutines.sync.Mutex
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

    /** Serialises exports — two STOPs in quick succession must not both start a Transformer. */
    private val exportLock = Mutex()

    /** Display rotation baked into the recorded files. Pushed down by the Activity. */
    @Volatile
    private var targetRotation: Int = Surface.ROTATION_0

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

    /** Capture frame rate currently in effect — the rewind screen uses it for frame-stepping. */
    private val _captureFps = MutableStateFlow(60)
    val captureFps: StateFlow<Int> = _captureFps.asStateFlow()

    /** False when RECORD_AUDIO is missing, so the UI can say the buffer is silent. */
    private val _audioEnabled = MutableStateFlow(true)
    val audioEnabled: StateFlow<Boolean> = _audioEnabled.asStateFlow()

    /**
     * Device thermal pressure. Hours of 60fps capture in the sun will throttle a phone, and the
     * first symptom is dropped frames rather than an error — so surface it.
     *
     * Deliberately advisory only: the obvious "fix" of dropping resolution automatically would
     * force a camera rebind, which wipes the rolling buffer. Losing the last three minutes
     * mid-session is worse than a warm phone, so the choice stays with the user.
     */
    private val _overheating = MutableStateFlow(false)
    val overheating: StateFlow<Boolean> = _overheating.asStateFlow()
    private var thermal: Pair<PowerManager.OnThermalStatusChangedListener, PowerManager>? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepo = CaptureSettingsRepository(applicationContext)
        rotation = SegmentRotationManager(File(filesDir, Constants.SEGMENT_DIR))
        exporter = ClipExporter(applicationContext, rotation)
        watchAck = WatchAck(applicationContext)
        targetRotation = seedRotationFromDisplay()
        _audioEnabled.value = hasAudioPermission()
        startForegroundCompat()
        observeSettings()
        observeThermal()
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

    private var lastZoomSentAtMs = 0L

    /**
     * Pinch-to-zoom from the live screen. Clamped to the sensor's supported range and rate-limited:
     * a fast pinch fires touch events far quicker than the camera HAL can apply them, and pushing
     * every one just backs up a queue and makes the zoom stutter. ~30 Hz is smooth enough.
     */
    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        val now = System.currentTimeMillis()
        if (now - lastZoomSentAtMs < 33L) return
        lastZoomSentAtMs = now
        val z = _zoom.value
        cam.cameraControl.setZoomRatio(ratio.coerceIn(z.minRatio, z.maxRatio))
    }

    fun bufferCoverageMs(): Long = rotation.coverageMs()

    /** The rewind screen calls these so its snapshotted segments aren't pruned mid-selection. */
    fun holdBuffer() = rotation.holdPrune()
    fun releaseBuffer() = rotation.releasePrune()

    /**
     * Keep recorded files upright. Only the Activity has a visual context that knows the real
     * display rotation, so it pushes the value down; CameraX applies it without a rebind.
     */
    fun updateTargetRotation(displayRotation: Int) {
        if (displayRotation == targetRotation) return
        targetRotation = displayRotation
        preview.setTargetRotation(displayRotation)
        videoCapture?.setTargetRotation(displayRotation)
    }

    // ---- settings: rebind + wipe buffer when the encoder shape changes (plan §4 Phase 6) ----

    private fun observeSettings() {
        // Buffer length: apply live, no rebind needed.
        lifecycleScope.launch {
            settingsRepo.settings
                .map { it.bufferRetentionMs }
                .distinctUntilChanged()
                .collect { rotation.retentionMs = it }
        }
        // Resolution / fps: need a camera rebind + buffer wipe (can't concat mixed encoder shapes).
        // Bitrate changes ride along on the next rebind rather than thrashing mid slider-drag.
        lifecycleScope.launch {
            settingsRepo.settings
                .map { it.resolution to it.captureFps }
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
        rotation.clear() // segments at a different resolution can't be concatenated
        segmentLoop = null
        // `camera` is deliberately left set: bindCamera() needs it to detach the old zoom observer.
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
        val capture = VideoCapture.Builder(recorder)
            .setTargetFrameRate(Range(settings.captureFps, settings.captureFps))
            .setTargetRotation(targetRotation)
            .build()
        videoCapture = capture
        preview.setTargetRotation(targetRotation)
        _captureFps.value = settings.captureFps

        // Drop the previous binding's zoom observer first — rebinding on every settings change
        // would otherwise stack a new observer on each pass.
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        provider.unbindAll()
        // Bind preview + capture under one ViewPort so they share a field of view. Without it
        // CameraX gives each use case its own crop rect and the live preview shows a wider frame
        // than what actually lands in the recorded file — "the ends get cut off". 9:16 matches
        // every QualitySelector option (all 16:9); the preview surface is letterboxed to it by
        // PreviewView's FIT_CENTER so the live image is exactly what gets recorded.
        val viewPort = ViewPort.Builder(Rational(9, 16), targetRotation)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
        val useCaseGroup = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(capture)
            .build()
        camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup)
        camera?.cameraInfo?.zoomState?.observe(this) { zs ->
            _zoom.value = ZoomInfo(zs.zoomRatio, zs.minZoomRatio, zs.maxZoomRatio)
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun recordOneSegment() {
        val capture = videoCapture ?: return
        val requestedAtMs = System.currentTimeMillis()
        val file = rotation.newSegmentFile(requestedAtMs)
        val finalized = CompletableDeferred<Long>()

        val withAudio = hasAudioPermission() // plan §7: RECORD_AUDIO optional
        _audioEnabled.value = withAudio
        val pending = capture.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
        if (withAudio) pending.withAudioEnabled()

        // Wall-clock time of this segment's *first frame*. It is not `requestedAtMs`: camera
        // warm-up and muxer startup put the first frame measurably later, and treating the two as
        // equal makes every scrub position drift further from the truth the older it gets.
        // Back-compute it instead — `now - recordedDuration` is the encoder's own view of when it
        // began. Callback latency can only ever make a sample look later than reality, so the
        // smallest sample over the segment's life is the closest estimate.
        // All these callbacks run on the same main executor, so the var needs no synchronisation.
        var anchorMs = Long.MAX_VALUE
        fun sampleAnchor(recordedNanos: Long) {
            val recordedMs = recordedNanos / 1_000_000
            if (recordedMs > 0) anchorMs = minOf(anchorMs, System.currentTimeMillis() - recordedMs)
        }

        val recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Status -> sampleAnchor(event.recordingStats.recordedDurationNanos)
                is VideoRecordEvent.Finalize -> {
                    val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
                    sampleAnchor(event.recordingStats.recordedDurationNanos)
                    val startedAtMs = if (anchorMs != Long.MAX_VALUE) anchorMs else requestedAtMs
                    if (event.hasError() &&
                        event.error != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                    ) {
                        Log.w(TAG, "segment finalize error ${event.error}")
                        file.delete()
                        finalized.complete(0)
                    } else {
                        rotation.register(file, startedAtMs, durationMs)
                        finalized.complete(durationMs)
                    }
                }
                else -> Unit
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
            watchAck.send(WatchAck.Kind.ERROR, "REC 없이 STOP")
            return
        }
        runExport(window.first, window.second, notifyWatch = true)
    }

    /** Manual export from the rewind screen. [speed] < 1 saves a slow-motion clip. */
    fun exportManual(startMs: Long, endMs: Long, speed: Float = 1f) =
        runExport(startMs, endMs, notifyWatch = false, speed = speed)

    private fun runExport(startMs: Long, endMs: Long, notifyWatch: Boolean, speed: Float = 1f) {
        // tryLock (not withLock) so a second STOP while one export is running is rejected
        // outright rather than queued — the user gets told, instead of silently waiting.
        if (!exportLock.tryLock()) {
            if (notifyWatch) watchAck.send(WatchAck.Kind.ERROR, "저장 중")
            return
        }
        // Pin the buffer for the whole export. The rewind screen releases its own hold the
        // instant it closes — which is right after it calls this — so without a second hold
        // here the catch-up prune can delete the very segments this export still needs. That
        // showed up as "구간이 버퍼에 없어요" on short selections and as truncated saves.
        rotation.holdPrune()
        lifecycleScope.launch {
            try {
                _state.value = State.EXPORTING
                updateNotification()
                val result = exporter.export(startMs, endMs, speed)
                _lastExport.value = result
                if (notifyWatch) {
                    when (result) {
                        is ClipExporter.Result.Saved ->
                            watchAck.send(
                                if (result.partial) WatchAck.Kind.PARTIAL else WatchAck.Kind.SAVED,
                                "${result.savedDurationMs / 1000}s",
                            )
                        is ClipExporter.Result.Failed -> watchAck.send(WatchAck.Kind.ERROR, result.reason)
                        ClipExporter.Result.NothingToSave ->
                            watchAck.send(WatchAck.Kind.ERROR, "버퍼 없음")
                    }
                }
            } finally {
                rotation.releasePrune()
                exportLock.unlock()
                _state.value = if (segmentLoop?.isActive == true) State.BUFFERING else State.ERROR
                updateNotification()
            }
        }
    }

    override fun onDestroy() {
        instance = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) unregisterThermalListener()
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

    private fun observeThermal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) registerThermalListener()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun registerThermalListener() {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        fun apply(status: Int) {
            val hot = status >= PowerManager.THERMAL_STATUS_SEVERE
            if (hot && !_overheating.value) {
                Log.w(TAG, "thermal status $status — capture may start dropping frames")
            }
            _overheating.value = hot
        }
        val listener = PowerManager.OnThermalStatusChangedListener { status -> apply(status) }
        runCatching {
            pm.addThermalStatusListener(listener)
            apply(pm.currentThermalStatus)
            thermal = listener to pm
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun unregisterThermalListener() {
        thermal?.let { (listener, pm) -> runCatching { pm.removeThermalStatusListener(listener) } }
        thermal = null
    }

    /**
     * Best-effort starting rotation. A Service has no visual context, so this can legitimately
     * fail — the Activity corrects it via [updateTargetRotation] as soon as it binds.
     */
    private fun seedRotationFromDisplay(): Int = runCatching {
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }.getOrDefault(Surface.ROTATION_0)

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
            State.EXPORTING -> "클립 저장 중…"
            State.ERROR -> "버퍼 중지됨 — 앱을 다시 여세요"
            else -> if (markers.isArmed) "녹화 중 — 버퍼링" else getString(R.string.notif_recording_text)
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

        /**
         * The live instance while the service is running, or null.
         *
         * Lets [WatchMessageListenerService] hand a marker straight to the running service
         * instead of going through `startForegroundService`, which API 34 can refuse outright
         * for a camera-type service when the app is in the background — exactly the situation
         * the watch exists for. Cleared in [onDestroy], so it holds nothing after teardown.
         */
        @Volatile
        var instance: RecordingService? = null
            private set

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
