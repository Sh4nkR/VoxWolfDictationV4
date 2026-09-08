package com.voxwolf.dictation.text

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SPEC §4.5 — Stitcher (overlap seam resolution).
 *
 * Pure Kotlin, no Android dependency.
 */
class StitcherTest {

    private lateinit var stitcher: Stitcher

    @Before
    fun setUp() {
        stitcher = Stitcher()
    }

    @Test
    fun `N is 10`() {
        assertEquals(10, Stitcher.N)
    }

    @Test
    fun `first window is accepted directly`() {
        val result = stitcher.stitch(0, "Hello world this is a test")
        assertEquals("first", result.seamType)
        assertEquals("Hello world this is a test", result.text)
    }

    @Test
    fun `empty text returns empty seam type`() {
        val result = stitcher.stitch(0, "  ")
        assertEquals("empty", result.seamType)
    }

    @Test
    fun `matching overlap is detected and stitched`() {
        stitcher.stitch(0, "the quick brown fox jumps over the lazy dog today")
        val result = stitcher.stitch(1, "the lazy dog today the weather is nice")

        assertEquals("matched", result.seamType)
        assertTrue(result.overlapWords >= 2)
        assertTrue(result.text.contains("the weather is nice"))
        // Should not duplicate "the lazy dog today"
        val occurrences = Regex("the lazy dog today").findAll(result.text).count()
        assertEquals(1, occurrences)
    }

    @Test
    fun `no match joins with space when overlap under 2 words`() {
        stitcher.stitch(0, "apples oranges bananas")
        val result = stitcher.stitch(1, "completely different text here")

        assertEquals("unmatched", result.seamType)
        assertEquals(0, result.overlapWords)
        assertEquals("apples oranges bananas completely different text here", result.text)
    }

    @Test
    fun `case insensitive matching`() {
        stitcher.stitch(0, "The Quick Brown Fox")
        val result = stitcher.stitch(1, "the quick brown fox jumped")

        assertEquals("matched", result.seamType)
        assertTrue(result.overlapWords >= 2)
    }

    @Test
    fun `reset clears committed text`() {
        stitcher.stitch(0, "some existing text")
        stitcher.reset()

        val result = stitcher.stitch(0, "fresh start")
        assertEquals("first", result.seamType)
        assertEquals("fresh start", result.text)
    }

    @Test
    fun `getCommittedText returns current state`() {
        stitcher.stitch(0, "hello world")
        assertEquals("hello world", stitcher.getCommittedText())
    }

    @Test
    fun `three windows stitch correctly`() {
        stitcher.stitch(0, "one two three four five six seven eight nine ten")
        stitcher.stitch(1, "eight nine ten eleven twelve thirteen fourteen")
        val result = stitcher.stitch(2, "thirteen fourteen fifteen sixteen")

        val text = result.text
        // Each set of overlapping words should appear exactly once
        assertTrue(text.contains("one two three"))
        assertTrue(text.contains("fifteen sixteen"))
    }

    @Test
    fun `punctuation does not break matching`() {
        stitcher.stitch(0, "the house on the hill, was beautiful")
        val result = stitcher.stitch(1, "the hill was beautiful and the garden")

        assertEquals("matched", result.seamType)
        assertTrue(result.text.contains("garden"))
    }
}
