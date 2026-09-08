package com.voxwolf.dictation.audio

/**
 * SPEC §4.2 — Window cutter.
 *
 * Decides window boundaries using sample counts, never floats.
 * Uses VAD silence boundaries to find natural cut points.
 *
 * | Constant     | Samples @16kHz | ms    |
 * |-------------|---------------|-------|
 * | TARGET      | 128_000       | 8_000 |
 * | SEARCH_EARLY| 96_000        | 6_000 |
 * | SEARCH_LATE | 160_000       | 10_000|
 * | OVERLAP     | 16_000        | 1_000 |
 * | ADVANCE     | 112_000       | 7_000 |
 */
class WindowCutter(
    private val onWindowReady: (Window) -> Unit
) {
    companion object {
        const val TARGET = 128_000      // 8s
        const val SEARCH_EARLY = 96_000 // 6s
        const val SEARCH_LATE = 160_000 // 10s
        const val OVERLAP = 16_000      // 1s
        const val ADVANCE = 112_000     // 7s
    }

    private var windowStart = 0
    private var windowIndex = 0
    private var lastBoundary = -1

    fun reset() {
        windowStart = 0
        windowIndex = 0
        lastBoundary = -1
    }

    /**
     * Called by the capture loop whenever VAD reports a boundary.
     * @param boundarySample absolute sample position of the boundary
     */
    fun onVadBoundary(boundarySample: Int) {
        lastBoundary = boundarySample
    }

    /**
     * Called after each chunk write to check if a window should be cut.
     * @param writeCursor current position in the session buffer
     */
    fun onSamplesWritten(writeCursor: Int) {
        val windowLength = writeCursor - windowStart
        if (windowLength < SEARCH_EARLY) return

        // Within the search range: look for a VAD boundary
        if (windowLength in SEARCH_EARLY..SEARCH_LATE) {
            if (lastBoundary >= windowStart + SEARCH_EARLY) {
                // Found a boundary in the search range — cut here
                val cutEnd = lastBoundary
                emitWindow(cutEnd, "vad")
                return
            }
        }

        // Past the late boundary: force cut
        if (windowLength >= SEARCH_LATE) {
            val cutEnd = windowStart + SEARCH_LATE
            emitWindow(cutEnd, "forced")
        }
    }

    private fun emitWindow(endSample: Int, cutType: String) {
        val window = Window(
            idx = windowIndex,
            start = windowStart,
            end = endSample,
            cut = cutType,
            audioMs = ((endSample - windowStart) * 1000L / 16000).toInt()
        )
        windowIndex++

        // Next window starts at end - OVERLAP
        windowStart = endSample - OVERLAP
        lastBoundary = -1

        onWindowReady(window)
    }

    /**
     * Get the current window start position.
     */
    fun currentWindowStart(): Int = windowStart

    data class Window(
        val idx: Int,
        val start: Int,
        val end: Int,
        val cut: String,
        val audioMs: Int
    )
}
