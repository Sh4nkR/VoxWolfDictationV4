package com.voxwolf.dictation.harness

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.voxwolf.dictation.telemetry.Telemetry
import java.io.File

/**
 * SPEC §13.4 — Thermal and clock sampling for harness.
 *
 * Samples once per window: battery temperature and CPU frequency for
 * one performance-cluster core (Exynos 9825 big cluster: cpu6).
 *
 * Temperature from ACTION_BATTERY_CHANGED sticky broadcast (tenths of °C).
 * Frequency from /sys/devices/system/cpu/cpu6/cpufreq/scaling_cur_freq.
 * If unreadable → null, not a build failure.
 */
class ThermalSampler(
    private val context: Context,
    private val telemetry: Telemetry
) {
    companion object {
        private const val CPU_FREQ_PATH =
            "/sys/devices/system/cpu/cpu6/cpufreq/scaling_cur_freq"
    }

    fun sample() {
        val tempC = getBatteryTempC()
        val cpuMhz = getCpuFreqMhz()

        telemetry.emit("THERMAL", mapOf(
            "temp_c" to tempC,
            "cpu_mhz" to cpuMhz
        ))
    }

    private fun getBatteryTempC(): Double? {
        return try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (tenths > 0) tenths / 10.0 else null
        } catch (_: Exception) {
            null
        }
    }

    private fun getCpuFreqMhz(): Int? {
        return try {
            val file = File(CPU_FREQ_PATH)
            if (!file.canRead()) return null
            val khz = file.readText().trim().toIntOrNull() ?: return null
            khz / 1000
        } catch (_: Exception) {
            null
        }
    }
}
