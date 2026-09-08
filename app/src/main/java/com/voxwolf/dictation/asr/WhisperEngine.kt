package com.voxwolf.dictation.asr

import android.os.SystemClock
import com.voxwolf.dictation.audio.WindowCutter
import com.voxwolf.dictation.telemetry.Telemetry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SPEC §4 — Stage 2: whisper inference.
 *
 * Single inference thread drains the window queue.
 * whisper.cpp internally uses 4 worker threads — those are inside the native call.
 *
 * PCM-16 → float32 conversion (sample / 32768.0f) happens here into
 * a reused FloatArray(160_000), sized for the 10s worst-case window.
 */
class WhisperEngine(
    private val telemetry: Telemetry,
    private val onWindowTranscribed: (Int, String) -> Unit,
    private val onQueueDrained: (Int) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val N_THREADS = 4
        // 10s worst-case window at 16kHz
        private const val MAX_FLOAT_SAMPLES = 160_000
    }

    private var contextPtr: Long = 0L
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voxwolf-inference").apply {
            priority = Thread.NORM_PRIORITY
        }
    }

    private val windowQueue = LinkedBlockingQueue<WindowCutter.Window>()
    private val draining = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    // Reused float buffer — one allocation for the process lifetime (SPEC §4.8)
    private val floatBuffer = FloatArray(MAX_FLOAT_SAMPLES)

    fun initContext(modelPath: String): Boolean {
        contextPtr = WhisperNative.initContext(modelPath)
        return contextPtr != 0L
    }

    fun releaseContext() {
        if (contextPtr != 0L) {
            WhisperNative.freeContext(contextPtr)
            contextPtr = 0L
        }
    }

    /**
     * Submit a window for transcription.
     * Called from the capture thread via WindowCutter callback.
     */
    fun submitWindow(window: WindowCutter.Window, sessionBuffer: ShortArray) {
        windowQueue.offer(window)

        if (running.compareAndSet(false, true)) {
            executor.submit { processQueue(sessionBuffer) }
        }
    }

    /**
     * Signal that capture has stopped. Drain remaining windows.
     */
    fun startDraining(sessionBuffer: ShortArray) {
        draining.set(true)
        if (running.compareAndSet(false, true)) {
            executor.submit { processQueue(sessionBuffer) }
        }
    }

    fun purgeQueue() {
        windowQueue.clear()
        draining.set(false)
        running.set(false)
    }

    private fun processQueue(sessionBuffer: ShortArray) {
        try {
            while (true) {
                val window = windowQueue.poll() ?: break

                telemetry.emit("INFER_START", mapOf("idx" to window.idx))
                val startTime = SystemClock.elapsedRealtime()

                val text = transcribeWindow(window, sessionBuffer)

                val durMs = SystemClock.elapsedRealtime() - startTime
                val tokenCount = text.split("\\s+".toRegex()).filter { it.isNotEmpty() }.size

                telemetry.emit("INFER_END", mapOf(
                    "idx" to window.idx,
                    "dur_ms" to durMs,
                    "tokens" to tokenCount
                ))

                if (text.isEmpty()) {
                    // Empty inference is not an error — could be a silent window
                } else {
                    onWindowTranscribed(window.idx, text)
                }
            }
        } catch (e: Exception) {
            onError("INFER_FAILED")
            return
        } finally {
            running.set(false)
        }

        // Check if more windows arrived while we were finishing
        if (windowQueue.isNotEmpty() && running.compareAndSet(false, true)) {
            processQueue(sessionBuffer)
            return
        }

        // If draining and queue is empty, signal completion
        if (draining.get() && windowQueue.isEmpty()) {
            val backlog = 0 // Queue is empty now
            draining.set(false)
            onQueueDrained(backlog)
        }
    }

    private fun transcribeWindow(window: WindowCutter.Window, sessionBuffer: ShortArray): String {
        if (contextPtr == 0L) return ""

        val sampleCount = window.end - window.start
        if (sampleCount <= 0 || sampleCount > MAX_FLOAT_SAMPLES) return ""

        // PCM-16 → float32 conversion into reused buffer (SPEC §4.8)
        for (i in 0 until sampleCount) {
            floatBuffer[i] = sessionBuffer[window.start + i] / 32768.0f
        }

        // Create a correctly sized view for whisper
        val pcm = if (sampleCount == MAX_FLOAT_SAMPLES) {
            floatBuffer
        } else {
            floatBuffer.copyOf(sampleCount)
        }

        return WhisperNative.transcribe(contextPtr, pcm, N_THREADS)
    }
}
