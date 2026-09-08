package com.voxwolf.dictation.audio

/**
 * SPEC §4.4 — Pre-allocated PCM store.
 *
 * ShortArray(8_720_000) — 545s at 16 kHz, 17.44 MB on heap.
 * Allocated once, reused across sessions, never reallocated.
 * Ceiling enforced on the write cursor.
 */
class SessionBuffer {

    companion object {
        /** 545 seconds at 16 kHz = 8,720,000 samples */
        const val CAPACITY = 8_720_000
    }

    /** The backing PCM-16 store */
    val data: ShortArray = ShortArray(CAPACITY)

    /** Current write position (sample index) */
    @Volatile
    var writeCursor: Int = 0
        private set

    /**
     * Write samples from a read chunk into the buffer.
     * @return number of samples actually written, or -1 if ceiling reached.
     */
    fun write(source: ShortArray, offset: Int, count: Int): Int {
        val remaining = CAPACITY - writeCursor
        if (remaining <= 0) return -1

        val toWrite = minOf(count, remaining)
        System.arraycopy(source, offset, data, writeCursor, toWrite)
        writeCursor += toWrite

        // Return -1 if we just hit the ceiling
        return if (writeCursor >= CAPACITY && toWrite < count) -1 else toWrite
    }

    /**
     * Check if the buffer has reached its ceiling.
     */
    fun atCeiling(): Boolean = writeCursor >= CAPACITY

    /**
     * Reset for a new session.
     */
    fun reset() {
        writeCursor = 0
        // No need to zero the array — writeCursor is the truth
    }

    /**
     * Read a window of samples from the buffer.
     * Used by WindowCutter and WhisperEngine.
     */
    fun readWindow(start: Int, end: Int, dest: ShortArray, destOffset: Int = 0): Int {
        val count = end - start
        if (start < 0 || end > writeCursor || count <= 0) return 0
        System.arraycopy(data, start, dest, destOffset, count)
        return count
    }
}
