package com.voxwolf.dictation.text

/**
 * SPEC §4.5 — Overlap seam resolution.
 *
 * N = 10. Takes trailing N words of committed transcript and leading N words
 * of new window. LCS for seam, tie-break: prefer seam closest to nominal
 * overlap position.
 *
 * Pure, no Android dependency, fully unit-testable.
 */
class Stitcher {

    companion object {
        const val N = 10
    }

    private val committed = StringBuilder()

    fun reset() {
        committed.setLength(0)
    }

    /**
     * Stitch a new window's transcript onto the committed text.
     * @param windowIdx window index for telemetry
     * @param newText the transcribed text from the new window
     * @return StitchResult with the updated transcript and seam info
     */
    fun stitch(windowIdx: Int, newText: String): StitchResult {
        val trimmedNew = newText.trim()
        if (trimmedNew.isEmpty()) {
            return StitchResult(committed.toString(), seamType = "empty")
        }

        if (committed.isEmpty()) {
            committed.append(trimmedNew)
            return StitchResult(committed.toString(), seamType = "first")
        }

        val existingWords = tokenize(committed.toString())
        val newWords = tokenize(trimmedNew)

        // Take trailing N words of existing, leading N words of new
        val tail = existingWords.takeLast(N)
        val head = newWords.take(N)

        val seam = findLongestCommonSubsequence(tail, head)

        if (seam != null && seam.size >= 2) {
            // Found a seam — join at it, dropping the duplicate
            // Remove trailing words that are part of the seam from committed
            val commitWords = existingWords.toMutableList()
            val tailStart = existingWords.size - tail.size

            // Find where the seam starts in the tail
            val seamStartInTail = findSubsequenceStart(tail, seam)
            // Find where the seam ends in the head
            val seamEndInHead = findSubsequenceEnd(head, seam)

            // Remove everything from seam start onward in committed
            val keepCount = tailStart + seamStartInTail
            val keptWords = commitWords.take(keepCount)

            // Take everything from after the seam in the new text
            val newAfterSeam = newWords.drop(seamEndInHead)

            // Rejoin
            committed.setLength(0)
            committed.append(keptWords.joinToString(" "))
            if (keptWords.isNotEmpty() && seam.isNotEmpty()) {
                committed.append(" ")
            }
            committed.append(seam.joinToString(" "))
            if (newAfterSeam.isNotEmpty()) {
                committed.append(" ")
                committed.append(newAfterSeam.joinToString(" "))
            }

            return StitchResult(
                committed.toString(),
                seamType = "matched",
                overlapWords = seam.size
            )
        } else {
            // No match of ≥ 2 words — join with a single space
            committed.append(" ")
            committed.append(trimmedNew)
            return StitchResult(
                committed.toString(),
                seamType = "unmatched",
                overlapWords = 0
            )
        }
    }

    fun getCommittedText(): String = committed.toString()

    /**
     * Tokenize text into words for comparison.
     * Normalise: lowercase, strip punctuation, collapse whitespace.
     */
    private fun tokenize(text: String): List<String> {
        return text.split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
    }

    /**
     * Normalise a word for comparison: lowercase, strip punctuation.
     */
    private fun normalizeForCompare(word: String): String {
        return word.lowercase().replace(Regex("[^a-z0-9']"), "")
    }

    /**
     * Find the longest common subsequence of contiguous words between tail and head.
     * Returns the matching words from the tail, or null if < 2 words.
     * On tie, prefer the match closest to nominal overlap position.
     */
    private fun findLongestCommonSubsequence(
        tail: List<String>,
        head: List<String>
    ): List<String>? {
        var bestLen = 0
        var bestTailStart = -1
        var bestHeadStart = -1

        for (i in tail.indices) {
            for (j in head.indices) {
                var len = 0
                while (i + len < tail.size && j + len < head.size &&
                    normalizeForCompare(tail[i + len]) == normalizeForCompare(head[j + len])
                ) {
                    len++
                }
                if (len > bestLen || (len == bestLen && len > 0 && isBetterTiebreak(
                        i, j, bestTailStart, bestHeadStart, tail.size, head.size
                    ))
                ) {
                    bestLen = len
                    bestTailStart = i
                    bestHeadStart = j
                }
            }
        }

        if (bestLen < 2) return null
        return tail.subList(bestTailStart, bestTailStart + bestLen)
    }

    /**
     * SPEC §4.5 tie-break: prefer the seam closest to the nominal overlap position.
     */
    private fun isBetterTiebreak(
        tailStart: Int, headStart: Int,
        bestTailStart: Int, bestHeadStart: Int,
        tailSize: Int, headSize: Int
    ): Boolean {
        // Nominal overlap: tail end, head start — so ideal is tailStart near end of tail
        // and headStart near start of head
        val newDist = (tailSize - tailStart) + headStart
        val bestDist = (tailSize - bestTailStart) + bestHeadStart
        return newDist < bestDist
    }

    private fun findSubsequenceStart(words: List<String>, seam: List<String>): Int {
        for (i in words.indices) {
            if (i + seam.size <= words.size) {
                var match = true
                for (j in seam.indices) {
                    if (normalizeForCompare(words[i + j]) != normalizeForCompare(seam[j])) {
                        match = false
                        break
                    }
                }
                if (match) return i
            }
        }
        return 0
    }

    private fun findSubsequenceEnd(words: List<String>, seam: List<String>): Int {
        for (i in words.indices) {
            if (i + seam.size <= words.size) {
                var match = true
                for (j in seam.indices) {
                    if (normalizeForCompare(words[i + j]) != normalizeForCompare(seam[j])) {
                        match = false
                        break
                    }
                }
                if (match) return i + seam.size
            }
        }
        return seam.size
    }

    data class StitchResult(
        val text: String,
        val seamType: String,
        val overlapWords: Int = 0
    )
}
