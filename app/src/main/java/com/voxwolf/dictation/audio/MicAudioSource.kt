package com.voxwolf.dictation.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper

/**
 * SPEC §3 — AudioRecord-based microphone source.
 *
 * 16 kHz, mono, PCM-16, VOICE_RECOGNITION.
 * Buffer: max(getMinBufferSize * 4, 16000 * 2 * 2) bytes.
 * Capture-loss detection per §3.2.
 */
class MicAudioSource(
    private val audioManager: AudioManager,
    private val onCaptureLost: (String) -> Unit
) : AudioSource {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MIN_BUFFER_BYTES = SAMPLE_RATE * 2 * 2 // 2 seconds of headroom
    }

    private var audioRecord: AudioRecord? = null
    private var consecutiveZeroChunks = 0

    // Mic seizure detection via AudioRecordingCallback
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            // If our client's config disappears from the active list, mic was seized
            val record = audioRecord ?: return
            val ourSessionId = record.audioSessionId
            val stillActive = configs.any { it.clientAudioSessionId == ourSessionId }
            if (!stillActive) {
                onCaptureLost("MIC_SEIZED")
            }
        }
    }

    override fun start(): Boolean {
        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufSize <= 0) return false

        val bufferSize = maxOf(minBufSize * 4, MIN_BUFFER_BYTES)

        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return false
            }

            record.startRecording()

            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
                record.release()
                return false
            }

            audioRecord = record
            consecutiveZeroChunks = 0

            // Register for mic seizure detection
            audioManager.registerAudioRecordingCallback(
                recordingCallback,
                Handler(Looper.getMainLooper())
            )

            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalStateException) {
            false
        }
    }

    override fun stop() {
        try {
            audioManager.unregisterAudioRecordingCallback(recordingCallback)
        } catch (_: Exception) {}

        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {}

        try {
            audioRecord?.release()
        } catch (_: Exception) {}

        audioRecord = null
    }

    override fun read(buffer: ShortArray, offset: Int, count: Int): Int {
        val record = audioRecord ?: return -1

        // §3.2: Check recordingState each chunk
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            onCaptureLost("CAPTURE_LOST")
            return -1
        }

        // §3.2: Check state is still initialized
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            onCaptureLost("CAPTURE_LOST")
            return -1
        }

        val result = record.read(buffer, offset, count)

        // §3.2: Negative return = error
        if (result < 0) {
            val code = when (result) {
                AudioRecord.ERROR_INVALID_OPERATION -> "CAPTURE_LOST"  // -3
                AudioRecord.ERROR_DEAD_OBJECT -> "CAPTURE_LOST"        // -6
                else -> "CAPTURE_LOST"
            }
            onCaptureLost(code)
            return result
        }

        // §3.2: Detect dead mic returning zero samples
        // Digital silence is bit-exact zero; real silence never is
        if (result > 0) {
            var allZero = true
            for (i in offset until offset + result) {
                if (buffer[i] != 0.toShort()) {
                    allZero = false
                    break
                }
            }

            if (allZero) {
                consecutiveZeroChunks++
                if (consecutiveZeroChunks >= 3) { // 3 chunks = 300ms
                    onCaptureLost("CAPTURE_SILENT_DEAD")
                    return -1
                }
            } else {
                consecutiveZeroChunks = 0
            }
        }

        return result
    }
}
