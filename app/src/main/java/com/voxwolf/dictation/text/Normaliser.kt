package com.voxwolf.dictation.text

/**
 * SPEC §4.6 — Post-processing normalisation.
 *
 * Seven ordered rules, applied once to the fully stitched transcript.
 * Pure, no Android dependency, fully unit-testable.
 */
object Normaliser {

    // Whisper non-speech annotations to strip (Rule 7)
    private val NON_SPEECH_PATTERNS = listOf(
        Regex("\\[BLANK_AUDIO\\]", RegexOption.IGNORE_CASE),
        Regex("\\[SOUND\\]", RegexOption.IGNORE_CASE),
        Regex("\\(music\\)", RegexOption.IGNORE_CASE),
        Regex("\\[_BEG_\\]", RegexOption.IGNORE_CASE),
        Regex("\\*[^*]+\\*"),  // *stage directions*
    )

    /**
     * Apply all seven normalisation rules in order.
     */
    fun normalise(text: String): String {
        var result = text

        // Rule 7 first (strip non-speech annotations before spacing rules)
        // Actually spec says order 1-7, but 7 removes tokens that would interfere
        // with spacing rules. Apply in spec order.

        // Rule 1: Collapse runs of whitespace to one space
        result = result.replace(Regex("\\s+"), " ")

        // Rule 2: Remove space before , . ! ? ; : ) and after (
        result = result.replace(Regex("\\s+([,\\.!?;:)])"), "$1")
        result = result.replace(Regex("(\\()\\s+"), "$1")

        // Rule 3: Ensure exactly one space after , . ! ? ; : when followed by a word character
        result = result.replace(Regex("([,\\.!?;:])(?=\\w)"), "$1 ")

        // Rule 4: Collapse repeated terminal punctuation (.. → ., ?? → ?, ?. → ?)
        result = result.replace(Regex("([.!?])\\1+"), "$1")
        result = result.replace(Regex("\\?\\."), "?")
        result = result.replace(Regex("\\.\\?"), "?")
        result = result.replace(Regex("!\\?"), "!")
        result = result.replace(Regex("\\?!"), "?")

        // Rule 5: Capitalise first alphabetic character and after . ! ?
        result = capitaliseFirst(result)
        result = capitaliseAfterTerminals(result)

        // Rule 6: Trim leading and trailing whitespace
        result = result.trim()

        // Rule 7: Strip Whisper's non-speech annotations
        for (pattern in NON_SPEECH_PATTERNS) {
            result = pattern.replace(result, "")
        }

        // Clean up any residual spacing from annotation removal
        result = result.replace(Regex("\\s+"), " ").trim()

        return result
    }

    private fun capitaliseFirst(text: String): String {
        val idx = text.indexOfFirst { it.isLetter() }
        if (idx < 0) return text
        return text.substring(0, idx) +
                text[idx].uppercaseChar() +
                text.substring(idx + 1)
    }

    private fun capitaliseAfterTerminals(text: String): String {
        val sb = StringBuilder(text)
        var i = 0
        while (i < sb.length) {
            if (sb[i] == '.' || sb[i] == '!' || sb[i] == '?') {
                // Skip whitespace after the terminal
                var j = i + 1
                while (j < sb.length && sb[j].isWhitespace()) j++
                // Capitalise the next letter
                if (j < sb.length && sb[j].isLetter()) {
                    sb[j] = sb[j].uppercaseChar()
                }
                i = j
            } else {
                i++
            }
        }
        return sb.toString()
    }
}
