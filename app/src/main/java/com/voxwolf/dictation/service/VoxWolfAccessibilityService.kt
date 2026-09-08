package com.voxwolf.dictation.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * SPEC §11.1 — Accessibility service for node access and focus tracking.
 *
 * Monitors focused editable fields to show/hide the bubble.
 * Provides node access for the Inserter.
 * Content-changed events throttled: re-evaluate bubble visibility only
 * when the focused-node identity actually changed.
 */
class VoxWolfAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: VoxWolfAccessibilityService? = null
            private set
    }

    /** Callback for bubble visibility changes */
    var onEditableFieldFocused: ((Boolean, String?) -> Unit)? = null

    private var lastFocusedNodeId: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                evaluateFocusedField()
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // Selection changed — node is still the same, no re-evaluation needed
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // §11.1: Throttle — only re-evaluate when focused-node identity changed
                val source = event.source
                if (source != null) {
                    val nodeId = nodeIdentity(source)
                    if (nodeId != lastFocusedNodeId) {
                        evaluateFocusedField()
                    }
                    source.recycle()
                }
            }
        }
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Check if there is currently a focused editable field.
     */
    private fun evaluateFocusedField() {
        val root = rootInActiveWindow ?: run {
            notifyFocusChange(false, null)
            return
        }

        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val pkg = focused.packageName?.toString()
            lastFocusedNodeId = nodeIdentity(focused)
            notifyFocusChange(true, pkg)
            focused.recycle()
        } else {
            // DFS fallback
            val editable = findEditableFocused(root)
            if (editable != null) {
                val pkg = editable.packageName?.toString()
                lastFocusedNodeId = nodeIdentity(editable)
                notifyFocusChange(true, pkg)
                editable.recycle()
            } else {
                lastFocusedNodeId = null
                notifyFocusChange(false, null)
            }
            focused?.recycle()
        }

        root.recycle()
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

    private fun notifyFocusChange(hasFocus: Boolean, packageName: String?) {
        onEditableFieldFocused?.invoke(hasFocus, packageName)
    }

    private fun nodeIdentity(node: AccessibilityNodeInfo): String {
        return "${node.packageName}:${node.viewIdResourceName}:${node.className}"
    }
}
