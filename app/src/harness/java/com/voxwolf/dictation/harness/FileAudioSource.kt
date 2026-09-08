package com.voxwolf.dictation.harness

import com.voxwolf.dictation.audio.AudioSource
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SPEC §13.1 — File-backed audio source for harness variant.
 *
 * Streams a 16 kHz mono PCM-16 WAV file in realtime pacing.
 * Deadline scheduling, not Thread.sleep(100) per chunk.
 * Deadline for chunk n is t0 + n * 100ms.
 *
 * On EOF, stops producing but does NOT stop the session —
 * session ends only on HARNESS_MIC_OFF.
 */
class FileAudioSource : AudioSource {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 1600 // 100ms
        private const val CHUNK_DURATION_NS = 100_000_000L // 100ms in nanoseconds
    }

    private var filePath: String? = null
    private var raf: RandomAccessFile? = null
    private var dataOffset: Int = 0
    private var dataSize: Int = 0
    private var bytesRead: Int = 0
    private var chunkCount: Long = 0
    private var startTimeNanos: Long = 0
    private var eof = false

    fun setFile(path: String) {
        filePath = path
    }

    override fun start(): Boolean {
        val path = filePath ?: return false
        val file = File(path)
        if (!file.exists()) return false

        return try {
            raf = RandomAccessFile(file, "r")
            parseWavHeader()
            bytesRead = 0
            chunkCount = 0
            eof = false
            startTimeNanos = System.nanoTime()
            true
        } catch (e: Exception) {
            raf?.close()
            raf = null
            false
        }
    }

    override fun stop() {
        try {
            raf?.close()
        } catch (_: Exception) {}
        raf = null
    }

    override fun read(buffer: ShortArray, offset: Int, count: Int): Int {
        if (eof) {
            // After EOF, return silence (zeros) to keep session alive
            // Session ends only on HARNESS_MIC_OFF
            sleepUntilDeadline()
            for (i in offset until offset + count) {
                buffer[i] = 0
            }
            return count
        }

        val file = raf ?: return -1

        // Deadline scheduling: wait until this chunk's deadline
        sleepUntilDeadline()

        val samplesToRead = minOf(count, CHUNK_SAMPLES)
        val bytesToRead = samplesToRead * 2
        val remainingData = dataSize - bytesRead

        if (remainingData <= 0) {
            eof = true
            for (i in offset until offset + count) {
                buffer[i] = 0
            }
            return count
        }

        val actualBytes = minOf(bytesToRead, remainingData)
        val actualSamples = actualBytes / 2
        val byteBuffer = ByteArray(actualBytes)

        try {
            file.seek((dataOffset + bytesRead).toLong())
            val read = file.read(byteBuffer, 0, actualBytes)
            if (read <= 0) {
                eof = true
                for (i in offset until offset + count) buffer[i] = 0
                return count
            }

            bytesRead += read
            chunkCount++

            // Convert bytes to shorts (little-endian PCM-16)
            val bb = ByteBuffer.wrap(byteBuffer, 0, read).order(ByteOrder.LITTLE_ENDIAN)
            val samplesRead = read / 2
            for (i in 0 until samplesRead) {
                buffer[offset + i] = bb.short
            }

            // Fill remainder with zeros if short read
            for (i in samplesRead until count) {
                buffer[offset + i] = 0
            }

            return samplesRead
        } catch (e: Exception) {
            return -1
        }
    }

    /**
     * Deadline scheduling per SPEC §13.1:
     * Deadline for chunk n is t0 + n * 100ms.
     * Sleep the remainder.
     */
    private fun sleepUntilDeadline() {
        val deadline = startTimeNanos + chunkCount * CHUNK_DURATION_NS
        val now = System.nanoTime()
        val sleepNanos = deadline - now
        if (sleepNanos > 0) {
            try {
                Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Parse WAV header to find the data chunk offset and size.
     */
    private fun parseWavHeader() {
        val file = raf ?: throw IllegalStateException("File not open")
        file.seek(0)

        val header = ByteArray(44)
        file.readFully(header)

        // Verify RIFF header
        val riff = String(header, 0, 4)
        val wave = String(header, 8, 4)
        require(riff == "RIFF" && wave == "WAVE") { "Not a valid WAV file" }

        // Find the data chunk (may not be at offset 44 in all WAV files)
        file.seek(12)
        while (file.filePointer < file.length()) {
            val chunkId = ByteArray(4)
            file.readFully(chunkId)
            val id = String(chunkId)

            val sizeBytes = ByteArray(4)
            file.readFully(sizeBytes)
            val size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).int

            if (id == "data") {
                dataOffset = file.filePointer.toInt()
                dataSize = size
                return
            }

            // Skip this chunk
            file.skipBytes(size)
        }

        throw IllegalStateException("No data chunk found in WAV file")
    }
}
