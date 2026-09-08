package com.voxwolf.dictation.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * SPEC §4.3 — Energy VAD with adaptive noise floor.
 *
 * Pure function over frames — no Android dependency, fully unit-testable.
 *
 * frame        = 480 samples (30 ms), no overlap
 * rms_dbfs(f)  = 20 * log10( sqrt(mean(sample²)) / 32768 )
 * noise_floor  = 20th percentile of rms_dbfs over trailing 100 frames (3.0 s),
 *                recomputed every 10 frames, initialised to -60 dBFS
 * speech(f)    = rms_dbfs(f) > noise_floor + 6.0 dB
 * boundary     = first frame of a run of ≥ 8 consecutive non-speech frames (240 ms)
 */
class Vad {

    companion object {
        /** Frame size: 480 samples = 30 ms at 16 kHz */
        const val FRAME_SIZE = 480

        /** Threshold above noise floor for speech detection */
        private const val THRESHOLD_DB = 6.0

        /** Consecutive non-speech frames to declare a boundary */
        private const val HANGOVER_FRAMES = 8

        /** Trailing window for noise floor estimation */
        private const val HISTORY_SIZE = 100

        /** Noise floor recomputation interval */
        private const val RECOMPUTE_INTERVAL = 10

        /** Initial noise floor */
        private const val INITIAL_NOISE_FLOOR = -60.0

        /** Silence duration that triggers VAD_SILENCE telemetry (ms) */
        const val SILENCE_TELEMETRY_THRESHOLD_MS = 2000

        /** Minimum dBFS to avoid log(0) */
        private const val MIN_DBFS = -96.0
    }

    private val history = DoubleArray(HISTORY_SIZE)
    private var historyIndex = 0
    private var historyCount = 0
    private var framesSinceRecompute = 0
    private var noiseFloor = INITIAL_NOISE_FLOOR
    private var consecutiveNonSpeech = 0
    private var boundaryReported = false
    private var silentRunStartSample = -1

    /** Total frames processed */
    var totalFrames = 0
        private set

    fun reset() {
        historyIndex = 0
        historyCount = 0
        framesSinceRecompute = 0
        noiseFloor = INITIAL_NOISE_FLOOR
        consecutiveNonSpeech = 0
        boundaryReported = false
        silentRunStartSample = -1
        totalFrames = 0
    }

    /**
     * Process one frame of 480 samples.
     * @param samples PCM-16 buffer
     * @param offset start index in the buffer
     * @return a VadResult indicating speech/non-speech and any boundary
     */
    fun processFrame(samples: ShortArray, offset: Int): VadResult {
        val rmsDbfs = computeRmsDbfs(samples, offset)

        // Record in history
        history[historyIndex] = rmsDbfs
        historyIndex = (historyIndex + 1) % HISTORY_SIZE
        if (historyCount < HISTORY_SIZE) historyCount++

        // Recompute noise floor every 10 frames
        framesSinceRecompute++
        if (framesSinceRecompute >= RECOMPUTE_INTERVAL) {
            noiseFloor = computePercentile20()
            framesSinceRecompute = 0
        }

        val isSpeech = rmsDbfs > noiseFloor + THRESHOLD_DB
        val frameSamplePosition = totalFrames * FRAME_SIZE
        totalFrames++

        if (!isSpeech) {
            consecutiveNonSpeech++

            if (silentRunStartSample < 0) {
                silentRunStartSample = frameSamplePosition
            }

            // Boundary: first frame of a run of ≥ 8 consecutive non-speech frames
            if (consecutiveNonSpeech == HANGOVER_FRAMES && !boundaryReported) {
                boundaryReported = true
                // Boundary is at the START of the silent run
                return VadResult(
                    isSpeech = false,
                    boundary = silentRunStartSample,
                    silentRunMs = consecutiveNonSpeech * 30
                )
            }

            return VadResult(
                isSpeech = false,
                boundary = -1,
                silentRunMs = consecutiveNonSpeech * 30
            )
        } else {
            consecutiveNonSpeech = 0
            boundaryReported = false
            silentRunStartSample = -1
            return VadResult(isSpeech = true, boundary = -1, silentRunMs = 0)
        }
    }

    private fun computeRmsDbfs(samples: ShortArray, offset: Int): Double {
        var sumSquares = 0.0
        for (i in 0 until FRAME_SIZE) {
            val s = samples[offset + i].toDouble()
            sumSquares += s * s
        }
        val rms = sqrt(sumSquares / FRAME_SIZE)
        return if (rms < 1.0) MIN_DBFS
        else 20.0 * log10(rms / 32768.0)
    }

    /**
     * 20th percentile of the history buffer.
     */
    private fun computePercentile20(): Double {
        if (historyCount == 0) return INITIAL_NOISE_FLOOR

        val sorted = if (historyCount < HISTORY_SIZE) {
            history.take(historyCount).toDoubleArray().apply { sort() }
        } else {
            history.clone().apply { sort() }
        }

        val idx = (historyCount * 0.2).toInt().coerceIn(0, historyCount - 1)
        return sorted[idx]
    }

    data class VadResult(
        val isSpeech: Boolean,
        /** Sample index of a silence boundary, or -1 if none */
        val boundary: Int,
        /** Current silent run duration in ms */
        val silentRunMs: Int
    )
}
