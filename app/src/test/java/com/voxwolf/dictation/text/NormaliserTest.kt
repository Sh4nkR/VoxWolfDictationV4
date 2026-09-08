package com.voxwolf.dictation.text

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SPEC §4.6 — Normaliser (7 ordered rules).
 *
 * Pure Kotlin, no Android dependency.
 */
class NormaliserTest {

    @Test
    fun `rule 1 — collapse whitespace`() {
        assertEquals("Hello world", Normaliser.normalise("Hello   world"))
        assertEquals("A b c", Normaliser.normalise("A  b   c"))
    }

    @Test
    fun `rule 2 — remove space before punctuation`() {
        assertEquals("Hello, world", Normaliser.normalise("Hello , world"))
        assertEquals("Done.", Normaliser.normalise("Done ."))
        assertEquals("Really?", Normaliser.normalise("Really ?"))
    }

    @Test
    fun `rule 3 — ensure space after punctuation`() {
        assertEquals("Hello, world", Normaliser.normalise("Hello,world"))
        assertEquals("Done. Next", Normaliser.normalise("Done.Next"))
    }

    @Test
    fun `rule 4 — collapse repeated terminals`() {
        assertEquals("What?", Normaliser.normalise("What??"))
        assertEquals("No!", Normaliser.normalise("No!!"))
        assertEquals("End.", Normaliser.normalise("End.."))
    }

    @Test
    fun `rule 5 — capitalise first character`() {
        assertEquals("Hello world", Normaliser.normalise("hello world"))
    }

    @Test
    fun `rule 5 — capitalise after terminals`() {
        assertEquals("Done. Next word", Normaliser.normalise("done. next word"))
        assertEquals("Really? Yes indeed", Normaliser.normalise("really? yes indeed"))
        assertEquals("Wow! That is great", Normaliser.normalise("wow! that is great"))
    }

    @Test
    fun `rule 6 — trim whitespace`() {
        assertEquals("Hello", Normaliser.normalise("  Hello  "))
    }

    @Test
    fun `rule 7 — strip whisper non-speech annotations`() {
        assertEquals("Hello world", Normaliser.normalise("Hello [BLANK_AUDIO] world"))
        assertEquals("Hello world", Normaliser.normalise("Hello [SOUND] world"))
        assertEquals("Hello world", Normaliser.normalise("Hello (music) world"))
        assertEquals("Hello world", Normaliser.normalise("Hello [_BEG_] world"))
        assertEquals("Hello world", Normaliser.normalise("Hello *clears throat* world"))
    }

    @Test
    fun `combined rules`() {
        val input = "  hello ,  world .  this  is  [BLANK_AUDIO]  a  test .  "
        val result = Normaliser.normalise(input)
        assertEquals("Hello, world. This is a test.", result)
    }

    @Test
    fun `empty input`() {
        assertEquals("", Normaliser.normalise(""))
        assertEquals("", Normaliser.normalise("   "))
    }

    @Test
    fun `annotation-only input produces empty`() {
        assertEquals("", Normaliser.normalise("[BLANK_AUDIO]"))
        assertEquals("", Normaliser.normalise("[SOUND] [BLANK_AUDIO]"))
    }

    @Test
    fun `mixed case annotations`() {
        assertEquals("Test", Normaliser.normalise("[blank_audio] test"))
        assertEquals("Test", Normaliser.normalise("[Blank_Audio] test"))
    }

    @Test
    fun `question and exclamation collapse`() {
        assertEquals("What?", Normaliser.normalise("What?."))
        assertEquals("Done!", Normaliser.normalise("Done!?"))
    }
}
