package com.voxwolf.dictation.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.voxwolf.dictation.telemetry.Telemetry

/**
 * SPEC §9 — Haptic map. Six events, six distinct patterns.
 *
 * Uses VibrationEffect.createWaveform with explicit timings and amplitudes.
 * Falls back to timing-only when hasAmplitudeControl() is false.
 * VibrationAttributes.USAGE_ACCESSIBILITY so haptics survive DND and silent mode.
 */
class Haptics(context: Context, private val telemetry: Telemetry) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val hasAmplitude = vibrator.hasAmplitudeControl()

    // ---- Amplitude waveforms (SPEC §9) ----
    private val ampTimings = mapOf(
        HapticEvent.BUBBLE_EXPANDED to longArrayOf(0, 12),
        HapticEvent.MIC_ON to longArrayOf(0, 45, 60, 45),
        HapticEvent.MIC_OFF to longArrayOf(0, 45, 60, 45),
        HapticEvent.TRANSCRIPT_INSERTED to longArrayOf(0, 30, 40, 30, 40, 30),
        HapticEvent.SESSION_CANCELLED to longArrayOf(0, 320),
        HapticEvent.ERROR to longArrayOf(0, 200, 90, 70, 90, 200)
    )

    private val ampAmplitudes = mapOf(
        HapticEvent.BUBBLE_EXPANDED to intArrayOf(0, 90),
        HapticEvent.MIC_ON to intArrayOf(0, 130, 0, 255),
        HapticEvent.MIC_OFF to intArrayOf(0, 255, 0, 130),
        HapticEvent.TRANSCRIPT_INSERTED to intArrayOf(0, 200, 0, 200, 0, 200),
        HapticEvent.SESSION_CANCELLED to intArrayOf(0, 160),
        HapticEvent.ERROR to intArrayOf(0, 255, 0, 255, 0, 255)
    )

    // ---- Timing-only fallback (SPEC §9 v4.1) ----
    // Mic ON/OFF re-differentiated via rising/falling duration
    private val fallbackTimings = mapOf(
        HapticEvent.BUBBLE_EXPANDED to longArrayOf(0, 12),
        HapticEvent.MIC_ON to longArrayOf(0, 30, 60, 90),
        HapticEvent.MIC_OFF to longArrayOf(0, 90, 60, 30),
        HapticEvent.TRANSCRIPT_INSERTED to longArrayOf(0, 30, 40, 30, 40, 30),
        HapticEvent.SESSION_CANCELLED to longArrayOf(0, 320),
        HapticEvent.ERROR to longArrayOf(0, 200, 90, 70, 90, 200)
    )

    fun fire(event: HapticEvent) {
        val mode: String
        val effect: VibrationEffect

        if (hasAmplitude) {
            mode = "amplitude"
            val timings = ampTimings[event]!!
            val amplitudes = ampAmplitudes[event]!!
            effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            mode = "timing_only"
            val timings = fallbackTimings[event]!!
            effect = VibrationEffect.createWaveform(timings, -1)
        }

        // USAGE_ACCESSIBILITY — survives DND and silent mode (SPEC §9 v4.1)
        @Suppress("DEPRECATION")
        vibrator.vibrate(effect)

        telemetry.emit("HAPTIC", mapOf(
            "event" to event.name,
            "mode" to mode
        ))
    }
}

enum class HapticEvent {
    BUBBLE_EXPANDED,
    MIC_ON,
    MIC_OFF,
    TRANSCRIPT_INSERTED,
    SESSION_CANCELLED,
    ERROR
}
