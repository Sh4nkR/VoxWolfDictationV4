package com.voxwolf.dictation.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.voxwolf.dictation.R
import com.voxwolf.dictation.VoxWolfApp
import com.voxwolf.dictation.asr.ModelProvisioner
import com.voxwolf.dictation.asr.WhisperEngine
import com.voxwolf.dictation.audio.AudioSource
import com.voxwolf.dictation.audio.MicAudioSource
import com.voxwolf.dictation.audio.SessionBuffer
import com.voxwolf.dictation.audio.Vad
import com.voxwolf.dictation.audio.WindowCutter
import com.voxwolf.dictation.haptics.HapticEvent
import com.voxwolf.dictation.haptics.Haptics
import com.voxwolf.dictation.insert.Inserter
import com.voxwolf.dictation.state.SessionEvent
import com.voxwolf.dictation.state.SessionState
import com.voxwolf.dictation.state.SessionStateMachine
import com.voxwolf.dictation.telemetry.Telemetry
import com.voxwolf.dictation.text.Normaliser
import com.voxwolf.dictation.text.Stitcher
import com.voxwolf.dictation.ui.BubbleOverlay

/**
 * SPEC §2 — Composition root. The foreground service that owns the pipeline.
 *
 * Lifecycle:
 *   startForeground → model provision → bubble shown
 *   EXPANDED → user taps mic → RECORDING → capture thread spins
 *   MIC_OFF → DRAINING → queue drains → INSERTING → verify → DELIVERED/ERROR
 *
 * The CaptureService is the single owner of every pipeline component.
 * Nothing is static; everything is wired here.
 */
class CaptureService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CAPTURE_CHUNK_SAMPLES = 1600 // 100ms at 16kHz
        private const val DELIVERED_TIMEOUT_MS = 2000L
    }

    // Pipeline components — all owned by this service
    private lateinit var telemetry: Telemetry
    private lateinit var sessionBuffer: SessionBuffer
    private lateinit var stateMachine: SessionStateMachine
    private lateinit var haptics: Haptics
    private lateinit var modelProvisioner: ModelProvisioner
    private lateinit var whisperEngine: WhisperEngine
    private lateinit var vad: Vad
    private lateinit var windowCutter: WindowCutter
    private lateinit var stitcher: Stitcher
    private lateinit var inserter: Inserter
    private lateinit var bubbleOverlay: BubbleOverlay

    private var audioSource: AudioSource? = null
    private var captureThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Timing
    private var stopPressedTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        val app = application as VoxWolfApp
        telemetry = app.telemetry
        sessionBuffer = app.sessionBuffer

        // Model provisioning
        modelProvisioner = ModelProvisioner(this)

        // Whisper engine
        whisperEngine = WhisperEngine(
            telemetry = telemetry,
            onWindowTranscribed = ::onWindowTranscribed,
            onQueueDrained = ::onQueueDrained,
            onError = ::onInferenceError
        )

        // Audio pipeline
        vad = Vad()
        windowCutter = WindowCutter(onWindowReady = ::onWindowReady)
        stitcher = Stitcher()
        inserter = Inserter(telemetry)
        haptics = Haptics(this, telemetry)

        // State machine — all transitions dispatched here
        stateMachine = SessionStateMachine(telemetry, ::onTransition)

        // UI overlay
        bubbleOverlay = BubbleOverlay(this, telemetry, ::onBubbleEvent)

        // Start foreground
        startForeground(NOTIFICATION_ID, buildNotification())

        // Provision model then show bubble
        provisionModelAndShowBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        whisperEngine.releaseContext()
        bubbleOverlay.remove()
    }

    // ---- Model & Bubble ----

    private fun provisionModelAndShowBubble() {
        Thread {
            val result = modelProvisioner.provision()
            when (result) {
                is ModelProvisioner.ProvisionResult.Success -> {
                    val ctxOk = whisperEngine.initContext(result.path)
                    mainHandler.post {
                        if (ctxOk) {
                            telemetry.emit("MODEL_READY", mapOf("path" to result.path))
                            bubbleOverlay.show()
                        } else {
                            telemetry.emit("MODEL_INIT_FAILED")
                        }
                    }
                }
                is ModelProvisioner.ProvisionResult.Error -> {
                    telemetry.emit("MODEL_PROVISION_FAILED", mapOf("code" to result.code))
                }
            }
        }.start()
    }

    // ---- Bubble events → State machine ----

    private fun onBubbleEvent(event: SessionEvent) {
        stateMachine.on(event)
    }

    // ---- State transitions ----

    private fun onTransition(from: SessionState, to: SessionState, event: SessionEvent) {
        telemetry.emit("TRANSITION", mapOf(
            "from" to from.name,
            "to" to to.name,
            "event" to event.name
        ))

        when (to) {
            SessionState.EXPANDED -> {
                haptics.fire(HapticEvent.BUBBLE_EXPANDED)
                bubbleOverlay.expandPanel()
            }

            SessionState.RECORDING -> {
                haptics.fire(HapticEvent.MIC_ON)
                telemetry.newSession()
                startCapture()
                bubbleOverlay.setRecording(true)
            }

            SessionState.DRAINING -> {
                haptics.fire(HapticEvent.MIC_OFF)
                stopPressedTime = SystemClock.elapsedRealtime()
                telemetry.emit("STOP_PRESSED")
                stopCapture()
                whisperEngine.startDraining(sessionBuffer.data)
                bubbleOverlay.setDraining(true)
            }

            SessionState.INSERTING -> {
                performInsertion()
            }

            SessionState.DELIVERED -> {
                haptics.fire(HapticEvent.TRANSCRIPT_INSERTED)
                val stopToPaste = SystemClock.elapsedRealtime() - stopPressedTime
                telemetry.emit("STOP_TO_PASTE", mapOf("dur_ms" to stopToPaste))
                bubbleOverlay.showDelivered()
                // Auto-return to IDLE after timeout
                mainHandler.postDelayed({
                    stateMachine.on(SessionEvent.TIMEOUT)
                }, DELIVERED_TIMEOUT_MS)
            }

            SessionState.CANCELLED -> {
                haptics.fire(HapticEvent.SESSION_CANCELLED)
                stopCapture()
                whisperEngine.purgeQueue()
                resetPipeline()
                bubbleOverlay.collapsePanel()
                // Immediately go to IDLE
                stateMachine.on(SessionEvent.IMMEDIATE)
            }

            SessionState.ERROR -> {
                haptics.fire(HapticEvent.ERROR)
                stopCapture()
                whisperEngine.purgeQueue()
                resetPipeline()
                bubbleOverlay.showError()
            }

            SessionState.IDLE -> {
                bubbleOverlay.collapsePanel()
                resetPipeline()
            }
        }
    }

    // ---- Capture thread (Stage 1) ----

    private fun startCapture() {
        sessionBuffer.reset()
        vad.reset()
        windowCutter.reset()
        stitcher.reset()

        val source = createAudioSource()
        audioSource = source

        if (!source.start()) {
            mainHandler.post { stateMachine.on(SessionEvent.CAPTURE_LOSS) }
            return
        }

        captureThread = Thread({
            runCaptureLoop(source)
        }, "voxwolf-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Override point for harness variant — injects FileAudioSource instead of MicAudioSource.
     */
    protected open fun createAudioSource(): AudioSource {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        return MicAudioSource(audioManager) { reason ->
            mainHandler.post {
                telemetry.emit("CAPTURE_LOST", mapOf("reason" to reason))
                stateMachine.on(SessionEvent.CAPTURE_LOSS)
            }
        }
    }

    private fun runCaptureLoop(source: AudioSource) {
        val chunk = ShortArray(CAPTURE_CHUNK_SAMPLES)

        while (!Thread.currentThread().isInterrupted) {
            val read = source.read(chunk, 0, CAPTURE_CHUNK_SAMPLES)
            if (read <= 0) break

            val written = sessionBuffer.write(chunk, 0, read)
            if (written == -1) {
                // Buffer ceiling reached
                mainHandler.post {
                    telemetry.emit("BUFFER_CEILING")
                    stateMachine.on(SessionEvent.BUFFER_CEILING)
                }
                break
            }

            // VAD: process frames
            processVadFrames(chunk, read)

            // RMS for waveform display
            val rms = computeChunkRms(chunk, read)
            mainHandler.post { bubbleOverlay.pushRms(rms) }

            // Window cutter
            windowCutter.onSamplesWritten(sessionBuffer.writeCursor)
        }
    }

    private fun processVadFrames(chunk: ShortArray, sampleCount: Int) {
        var offset = 0
        while (offset + Vad.FRAME_SIZE <= sampleCount) {
            val result = vad.processFrame(chunk, offset)

            if (result.boundary >= 0) {
                windowCutter.onVadBoundary(result.boundary)
            }

            // SPEC §4.3: silence telemetry
            if (result.silentRunMs >= Vad.SILENCE_TELEMETRY_THRESHOLD_MS) {
                telemetry.emit("VAD_SILENCE", mapOf("ms" to result.silentRunMs))
            }

            offset += Vad.FRAME_SIZE
        }
    }

    private fun computeChunkRms(chunk: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val s = chunk[i].toDouble()
            sum += s * s
        }
        return (kotlin.math.sqrt(sum / count) / 32768.0).toFloat()
    }

    private fun stopCapture() {
        captureThread?.interrupt()
        captureThread = null
        audioSource?.stop()
        audioSource = null
    }

    // ---- Window callback (Stage 1 → Stage 2) ----

    private fun onWindowReady(window: WindowCutter.Window) {
        telemetry.emit("WINDOW_CUT", mapOf(
            "idx" to window.idx,
            "start" to window.start,
            "end" to window.end,
            "cut" to window.cut,
            "audio_ms" to window.audioMs
        ))
        whisperEngine.submitWindow(window, sessionBuffer.data)
    }

    // ---- Inference callbacks ----

    private fun onWindowTranscribed(windowIdx: Int, text: String) {
        val result = stitcher.stitch(windowIdx, text)
        telemetry.emit(
            if (result.seamType == "matched") "SEAM_MATCHED" else
                if (result.seamType == "unmatched") "SEAM_UNMATCHED" else "SEAM_${result.seamType.uppercase()}",
            mapOf(
                "idx" to windowIdx,
                "overlap_words" to result.overlapWords
            )
        )
    }

    private fun onQueueDrained(backlog: Int) {
        mainHandler.post {
            telemetry.emit("QUEUE_EMPTY", mapOf("backlog" to backlog))
            stateMachine.on(SessionEvent.QUEUE_EMPTY)
        }
    }

    private fun onInferenceError(code: String) {
        mainHandler.post {
            telemetry.emit("INFER_ERROR", mapOf("code" to code))
            stateMachine.on(SessionEvent.CAPTURE_LOSS)
        }
    }

    // ---- Insertion ----

    private fun performInsertion() {
        Thread {
            val transcript = Normaliser.normalise(stitcher.getCommittedText())
            if (transcript.isBlank()) {
                mainHandler.post {
                    telemetry.emit("INSERT_EMPTY")
                    stateMachine.on(SessionEvent.INSERT_VERIFIED)
                }
                return@Thread
            }

            val a11yService = VoxWolfAccessibilityService.instance
            if (a11yService == null) {
                mainHandler.post {
                    telemetry.emit("INSERT_FAILED", mapOf("code" to "NO_A11Y_SERVICE"))
                    stateMachine.on(SessionEvent.TARGET_LOST)
                }
                return@Thread
            }

            val result = inserter.insert(a11yService, transcript)
            mainHandler.post {
                when (result) {
                    is Inserter.InsertResult.Verified -> {
                        stateMachine.on(SessionEvent.INSERT_VERIFIED)
                    }
                    is Inserter.InsertResult.Failed -> {
                        stateMachine.on(SessionEvent.INSERT_FAILED)
                    }
                    is Inserter.InsertResult.TooLong -> {
                        telemetry.emit("INSERT_TOO_LONG", mapOf(
                            "max" to result.maxLen,
                            "composed" to result.composedLen
                        ))
                        stateMachine.on(SessionEvent.INSERT_FAILED)
                    }
                    is Inserter.InsertResult.TargetLost -> {
                        stateMachine.on(SessionEvent.TARGET_LOST)
                    }
                }
            }
        }.start()
    }

    // ---- Pipeline reset ----

    private fun resetPipeline() {
        sessionBuffer.reset()
        vad.reset()
        windowCutter.reset()
        stitcher.reset()
    }

    // ---- Notification ----

    private fun buildNotification(): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, VoxWolfApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_wolf_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
