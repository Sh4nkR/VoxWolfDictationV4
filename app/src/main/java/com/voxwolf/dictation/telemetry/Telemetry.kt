package com.voxwolf.dictation.telemetry

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * SPEC §13.3 — Telemetry emitter.
 *
 * One JSON object per line to logcat under tag VOXWOLF_T.
 * Monotonic clock (SystemClock.elapsedRealtime()), milliseconds.
 * Every line carries "sid" (session id) and "t" (timestamp).
 *
 * Single-threaded executor, file append (harness sink overrides).
 * Never blocks a caller. Losing a telemetry line is acceptable;
 * blocking capture is not.
 */
open class Telemetry {

    companion object {
        const val TAG = "VOXWOLF_T"
        private val sessionCounter = AtomicLong(0)
    }

    protected val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voxwolf-telemetry").apply {
            priority = Thread.MIN_PRIORITY
        }
    }

    var sessionId: Long = 0
        private set

    fun newSession(): Long {
        sessionId = sessionCounter.incrementAndGet()
        return sessionId
    }

    /**
     * Emit a telemetry event. Never blocks the caller.
     */
    open fun emit(event: String, fields: Map<String, Any?> = emptyMap()) {
        val t = SystemClock.elapsedRealtime()
        val sid = sessionId

        executor.execute {
            try {
                val json = JSONObject()
                json.put("t", t)
                json.put("sid", sid)
                json.put("ev", event)
                for ((key, value) in fields) {
                    json.put(key, value ?: JSONObject.NULL)
                }
                val line = json.toString()
                Log.i(TAG, line)
                writeLine(line)
            } catch (_: Exception) {
                // Losing a telemetry line is acceptable
            }
        }
    }

    /**
     * Override in harness to write to file.
     */
    protected open fun writeLine(line: String) {
        // Base implementation: logcat only
    }

    /**
     * Flush pending writes. Used by harness dump command.
     */
    open fun flush() {
        // Base: nothing to flush beyond logcat
    }
}
