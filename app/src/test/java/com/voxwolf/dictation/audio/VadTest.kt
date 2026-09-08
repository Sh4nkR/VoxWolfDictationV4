package com.voxwolf.dictation.audio

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SPEC §4.3 — Energy VAD.
 *
 * Pure Kotlin, no Android dependency.
 */
class VadTest {

    private lateinit var vad: Vad

    @Before
    fun setUp() {
        vad = Vad()
    }

    @Test
    fun `frame size is 480 samples`() {
        assertEquals(480, Vad.FRAME_SIZE)
    }

    @Test
    fun `silence frames produce non-speech result`() {
        val silent = ShortArray(Vad.FRAME_SIZE) // all zeros
        val result = vad.processFrame(silent, 0)
        assertFalse(result.isSpeech)
    }

    @Test
    fun `loud frames produce speech result`() {
        // First, feed some silent frames to establish noise floor
        val silent = ShortArray(Vad.FRAME_SIZE)
        repeat(20) { vad.processFrame(silent, 0) }

        // Now a loud frame
        val loud = ShortArray(Vad.FRAME_SIZE) { 10000 }
        val result = vad.processFrame(loud, 0)
        assertTrue(result.isSpeech)
    }

    @Test
    fun `boundary reported after 8 consecutive non-speech frames`() {
        // Feed loud frames first
        val loud = ShortArray(Vad.FRAME_SIZE) { 10000 }
        repeat(20) { vad.processFrame(loud, 0) }

        // Now 8 silent frames → boundary
        val silent = ShortArray(Vad.FRAME_SIZE)
        var boundaryFound = false
        for (i in 1..8) {
            val result = vad.processFrame(silent, 0)
            if (result.boundary >= 0) {
                boundaryFound = true
            }
        }
        assertTrue("Expected a boundary after 8 silent frames", boundaryFound)
    }

    @Test
    fun `no boundary for fewer than 8 silent frames`() {
        val loud = ShortArray(Vad.FRAME_SIZE) { 10000 }
        repeat(20) { vad.processFrame(loud, 0) }

        val silent = ShortArray(Vad.FRAME_SIZE)
        var boundaryFound = false
        for (i in 1..7) {
            val result = vad.processFrame(silent, 0)
            if (result.boundary >= 0) boundaryFound = true
        }
        assertFalse("No boundary should occur before 8 frames", boundaryFound)
    }

    @Test
    fun `reset clears all state`() {
        val loud = ShortArray(Vad.FRAME_SIZE) { 5000 }
        repeat(50) { vad.processFrame(loud, 0) }

        vad.reset()
        assertEquals(0, vad.totalFrames)

        // After reset, silent frames should not start with a boundary
        val silent = ShortArray(Vad.FRAME_SIZE)
        val result = vad.processFrame(silent, 0)
        assertEquals(-1, result.boundary)
    }

    @Test
    fun `silence telemetry threshold is 2000ms`() {
        assertEquals(2000, Vad.SILENCE_TELEMETRY_THRESHOLD_MS)
    }

    @Test
    fun `silentRunMs tracks duration correctly`() {
        val silent = ShortArray(Vad.FRAME_SIZE)
        // Each frame = 30ms
        val result1 = vad.processFrame(silent, 0)
        assertEquals(30, result1.silentRunMs)

        val result5 = run {
            var r = result1
            repeat(4) { r = vad.processFrame(silent, 0) }
            r
        }
        assertEquals(150, result5.silentRunMs) // 5 frames × 30ms

        // Speech resets
        val loud = ShortArray(Vad.FRAME_SIZE) { 15000 }
        // Need to first establish a noise floor from silence
        val speechResult = vad.processFrame(loud, 0)
        assertEquals(0, speechResult.silentRunMs)
    }

    @Test
    fun `offset parameter works correctly`() {
        // Put loud data at offset 480 in a larger buffer
        val buffer = ShortArray(960)
        for (i in 480 until 960) buffer[i] = 10000

        // First establish low noise floor
        val silent = ShortArray(Vad.FRAME_SIZE)
        repeat(20) { vad.processFrame(silent, 0) }

        // Process from offset 480
        val result = vad.processFrame(buffer, 480)
        assertTrue(result.isSpeech)
    }
}
