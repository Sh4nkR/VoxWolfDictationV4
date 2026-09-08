package com.voxwolf.dictation.insert

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.voxwolf.dictation.telemetry.Telemetry

/**
 * SPEC §5 — Text insertion via AccessibilityService.
 *
 * Five-step cursor contract (§5.1):
 * 1. Read existing text from focused node
 * 2. Read current selection start/end
 * 3. Compose result: existing text with transcript spliced at cursor
 * 4. ACTION_SET_TEXT with composed string
 * 5. ACTION_SET_SELECTION to place caret at end of inserted text
 *
 * Node resolved at insert time, not cached (§5.1).
 * Read-back polled 5x40ms (§5.2).
 */
class Inserter(
    private val telemetry: Telemetry
) {
    companion object {
        private const val READBACK_ATTEMPTS = 5
        private const val READBACK_INTERVAL_MS = 40L
    }

    /**
     * Insert transcript into the currently focused editable field.
     * @param service the accessibility service for node access
     * @param transcript the full stitched transcript to insert
     * @return InsertResult describing success or failure
     */
    fun insert(service: AccessibilityService, transcript: String): InsertResult {
        // §5.1 Node resolution at insert time
        val node = resolveTargetNode(service)
            ?: return InsertResult.TargetLost

        val packageName = node.packageName?.toString() ?: "unknown"
        telemetry.emit("INSERT_START", mapOf("pkg" to packageName))

        // Step 1: Read existing text
        val existingText = node.text?.toString() ?: ""

        // Step 2: Read current selection
        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd

        // Step 3: Compose result
        val composed: String
        val caretPosition: Int

        if (selStart == -1) {
            // No selection reported — splice at end
            composed = existingText + transcript
            caretPosition = composed.length
        } else if (selStart != selEnd && selStart >= 0 && selEnd >= 0) {
            // Active selection — replace it
            val before = existingText.substring(0, selStart.coerceIn(0, existingText.length))
            val after = existingText.substring(selEnd.coerceIn(0, existingText.length))
            composed = before + transcript + after
            caretPosition = before.length + transcript.length
        } else {
            // Cursor position, no selection
            val pos = selStart.coerceIn(0, existingText.length)
            val before = existingText.substring(0, pos)
            val after = existingText.substring(pos)
            composed = before + transcript + after
            caretPosition = before.length + transcript.length
        }

        // Check maxTextLength (§5.1)
        val maxLen = node.maxTextLength
        if (maxLen >= 0 && composed.length > maxLen) {
            node.recycle()
            return InsertResult.TooLong(packageName, maxLen, composed.length)
        }

        // Step 4: ACTION_SET_TEXT
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, composed)
        val setResult = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (!setResult) {
            node.recycle()
            telemetry.emit("INSERT_FAILED", mapOf(
                "pkg" to packageName,
                "code" to "NO_SET_TEXT",
                "attempts" to 0
            ))
            return InsertResult.Failed(packageName, "NO_SET_TEXT")
        }

        // Step 5: ACTION_SET_SELECTION to place caret
        val selArgs = Bundle()
        selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caretPosition)
        selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caretPosition)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)

        // §5.2 Read-back verification — polled 5x40ms
        val verified = verifyReadback(node, composed)
        node.recycle()

        if (!verified) {
            telemetry.emit("INSERT_FAILED", mapOf(
                "pkg" to packageName,
                "code" to "VERIFY_MISMATCH",
                "attempts" to READBACK_ATTEMPTS
            ))
            return InsertResult.Failed(packageName, "VERIFY_MISMATCH")
        }

        telemetry.emit("INSERT_VERIFIED", mapOf(
            "chars" to composed.length,
            "caret" to caretPosition
        ))

        return InsertResult.Verified(packageName, composed.length, caretPosition)
    }

    /**
     * §5.1 Node resolution order:
     * 1. rootInActiveWindow.findFocus(FOCUS_INPUT)
     * 2. DFS for first node with isEditable && isFocused
     * 3. null → TARGET_LOST
     */
    private fun resolveTargetNode(service: AccessibilityService): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null

        // Try 1: findFocus(FOCUS_INPUT)
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            return focused
        }
        focused?.recycle()

        // Try 2: DFS for editable + focused
        val found = findEditableFocused(root)
        if (found != null) return found

        root.recycle()
        return null
    }

    private fun findEditableFocused(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableFocused(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    /**
     * §5.2 — Polled read-back, 5 attempts at 40ms intervals.
     * Compare on exact string equality of the composed value.
     */
    private fun verifyReadback(node: AccessibilityNodeInfo, expected: String): Boolean {
        for (attempt in 1..READBACK_ATTEMPTS) {
            try {
                Thread.sleep(READBACK_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            node.refresh()
            val currentText = node.text?.toString() ?: ""
            if (currentText == expected) {
                return true
            }
        }
        return false
    }

    sealed class InsertResult {
        data class Verified(val pkg: String, val chars: Int, val caret: Int) : InsertResult()
        data class Failed(val pkg: String, val code: String) : InsertResult()
        data class TooLong(val pkg: String, val maxLen: Int, val composedLen: Int) : InsertResult()
        object TargetLost : InsertResult()
    }
}
