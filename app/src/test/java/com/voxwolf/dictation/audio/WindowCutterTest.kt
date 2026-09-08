package com.voxwolf.dictation.audio

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SPEC §4.2 — Window cutter.
 *
 * Pure Kotlin, no Android dependency.
 */
class WindowCutterTest {

    private lateinit var cutter: WindowCutter
    private val emittedWindows = mutableListOf<WindowCutter.Window>()

    @Before
    fun setUp() {
        emittedWindows.clear()
        cutter = WindowCutter { window -> emittedWindows.add(window) }
    }

    @Test
    fun `constants match spec`() {
        assertEquals(128_000, WindowCutter.TARGET)      // 8s
        assertEquals(96_000, WindowCutter.SEARCH_EARLY)  // 6s
        assertEquals(160_000, WindowCutter.SEARCH_LATE)  // 10s
        assertEquals(16_000, WindowCutter.OVERLAP)        // 1s
        assertEquals(112_000, WindowCutter.ADVANCE)       // 7s
    }

    @Test
    fun `no window emitted before SEARCH_EARLY`() {
        cutter.onSamplesWritten(95_999)
        assertTrue(emittedWindows.isEmpty())
    }

    @Test
    fun `forced cut at SEARCH_LATE when no VAD boundary`() {
        cutter.onSamplesWritten(160_000)
        assertEquals(1, emittedWindows.size)

        val window = emittedWindows[0]
        assertEquals(0, window.idx)
        assertEquals(0, window.start)
        assertEquals(160_000, window.end)
        assertEquals("forced", window.cut)
        assertEquals(10_000, window.audioMs) // 160000 / 16 = 10000ms
    }

    @Test
    fun `VAD boundary triggers cut within search range`() {
        // Simulate a VAD boundary at sample 100_000 (within 96k–160k range)
        cutter.onVadBoundary(100_000)
        cutter.onSamplesWritten(100_001)

        assertEquals(1, emittedWindows.size)
        val window = emittedWindows[0]
        assertEquals(100_000, window.end)
        assertEquals("vad", window.cut)
    }

    @Test
    fun `next window starts at end minus OVERLAP`() {
        cutter.onVadBoundary(100_000)
        cutter.onSamplesWritten(100_001)

        assertEquals(1, emittedWindows.size)

        // Second window: starts at 100_000 - 16_000 = 84_000
        // Force cut at 84_000 + 160_000 = 244_000
        cutter.onSamplesWritten(244_000)
        assertEquals(2, emittedWindows.size)

        val w2 = emittedWindows[1]
        assertEquals(1, w2.idx)
        assertEquals(84_000, w2.start) // 100000 - 16000
    }

    @Test
    fun `VAD boundary before SEARCH_EARLY is ignored`() {
        cutter.onVadBoundary(50_000) // Too early
        cutter.onSamplesWritten(96_000)

        // No cut because the boundary is before SEARCH_EARLY
        assertTrue(emittedWindows.isEmpty())
    }

    @Test
    fun `reset clears state`() {
        cutter.onSamplesWritten(160_000)
        assertEquals(1, emittedWindows.size)

        cutter.reset()
        emittedWindows.clear()

        cutter.onSamplesWritten(50_000)
        assertTrue(emittedWindows.isEmpty())
    }

    @Test
    fun `window audioMs computed correctly`() {
        cutter.onVadBoundary(128_000) // exact TARGET
        cutter.onSamplesWritten(128_001)

        val window = emittedWindows[0]
        assertEquals(8000, window.audioMs) // 128000 samples / 16 = 8000ms
    }

    @Test
    fun `window indices increment`() {
        // Emit 3 windows via forced cuts
        cutter.onSamplesWritten(160_000)
        // Next window starts at 160000 - 16000 = 144000
        cutter.onSamplesWritten(144_000 + 160_000)
        // Next starts at 288000 - 16000 = 272000
        cutter.onSamplesWritten(272_000 + 160_000)

        assertEquals(3, emittedWindows.size)
        assertEquals(0, emittedWindows[0].idx)
        assertEquals(1, emittedWindows[1].idx)
        assertEquals(2, emittedWindows[2].idx)
    }
}
