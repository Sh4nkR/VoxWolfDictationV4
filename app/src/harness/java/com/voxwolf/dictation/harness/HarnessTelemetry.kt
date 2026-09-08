package com.voxwolf.dictation.harness

import com.voxwolf.dictation.telemetry.Telemetry
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * SPEC §13.3 — Harness telemetry sink.
 *
 * Writes JSONL to /sdcard/voxwolf_test/telemetry.jsonl in addition to logcat.
 */
class HarnessTelemetry : Telemetry() {

    companion object {
        private const val OUTPUT_PATH = "/sdcard/voxwolf_test/telemetry.jsonl"
    }

    private var writer: BufferedWriter? = null

    init {
        try {
            val file = File(OUTPUT_PATH)
            file.parentFile?.mkdirs()
            writer = BufferedWriter(FileWriter(file, true))
        } catch (_: Exception) {
            // Fall back to logcat only
        }
    }

    override fun writeLine(line: String) {
        try {
            writer?.apply {
                write(line)
                newLine()
            }
        } catch (_: Exception) {
            // Losing a line is acceptable
        }
    }

    override fun flush() {
        executor.execute {
            try {
                writer?.flush()
            } catch (_: Exception) {}
        }
    }

    fun close() {
        executor.execute {
            try {
                writer?.flush()
                writer?.close()
            } catch (_: Exception) {}
        }
    }
}
