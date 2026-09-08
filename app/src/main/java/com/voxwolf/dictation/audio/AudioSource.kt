package com.voxwolf.dictation.audio

/**
 * SPEC §2.1 — AudioSource interface.
 * MicAudioSource for release, FileAudioSource for harness.
 */
interface AudioSource {
    /**
     * Start the audio source. Must be called before read().
     * @return true if started successfully.
     */
    fun start(): Boolean

    /**
     * Stop the audio source and release resources.
     */
    fun stop()

    /**
     * Read PCM-16 samples into the buffer.
     * @return number of samples read, or a negative error code.
     */
    fun read(buffer: ShortArray, offset: Int, count: Int): Int
}
