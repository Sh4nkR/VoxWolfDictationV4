package com.voxwolf.dictation.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.voxwolf.dictation.state.SessionEvent
import com.voxwolf.dictation.state.SessionState
import com.voxwolf.dictation.telemetry.Telemetry

/**
 * SPEC §8 — Floating overlay.
 *
 * Bubble: 56dp wolf face, draggable, snaps to nearest edge.
 * Panel: side-aware expansion (opens toward screen centre).
 * Tap vs drag discrimination: 10dp slop, 200ms hold.
 *
 * Position persisted across sessions via SharedPreferences.
 */
class BubbleOverlay(
    private val context: Context,
    private val telemetry: Telemetry,
    private val onEvent: (SessionEvent) -> Unit
) {
    companion object {
        private const val BUBBLE_SIZE_DP = 49 // ≈ 1.3 cm on a standard 160dpi baseline
        private const val TAP_SLOP_DP = 10
        private const val LONG_PRESS_MS = 500L
        private const val SNAP_ANIM_MS = 200L
        private const val PREFS_NAME = "voxwolf_bubble"
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val density = context.resources.displayMetrics.density
    private val bubbleSizePx = (BUBBLE_SIZE_DP * density).toInt()
    private val tapSlopPx = (TAP_SLOP_DP * density).toInt()

    // Views
    private var rootView: FrameLayout? = null
    private var wolfFaceView: WolfFaceView? = null
    private var panelView: PanelView? = null

    // Layout params
    private lateinit var layoutParams: WindowManager.LayoutParams

    // Drag state
    private var isDragging = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartParamX = 0
    private var touchStartParamY = 0
    private var touchStartTime = 0L

    // State
    private var isExpanded = false
    private var currentState = SessionState.IDLE

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (rootView != null) return

        val display = wm.defaultDisplay
        val screenWidth = display.width
        val screenHeight = display.height

        // Restore position or default to right edge, center Y
        val savedX = prefs.getInt(KEY_X, screenWidth - bubbleSizePx)
        val savedY = prefs.getInt(KEY_Y, screenHeight / 2 - bubbleSizePx / 2)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        // Build view hierarchy
        val root = FrameLayout(context)

        val wolfFace = WolfFaceView(context)
        wolfFace.layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx)
        root.addView(wolfFace)

        val panel = PanelView(context, telemetry, onMicPressed = {
            onEvent(SessionEvent.MIC_BUTTON)
        }, onCancelPressed = {
            onEvent(SessionEvent.RED_CROSS)
        })
        panel.visibility = View.GONE
        root.addView(panel)

        root.setOnTouchListener { _, event -> handleTouch(event) }

        rootView = root
        wolfFaceView = wolfFace
        panelView = panel

        wm.addView(root, layoutParams)
        telemetry.emit("BUBBLE_SHOWN")
    }

    fun remove() {
        rootView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        rootView = null
        wolfFaceView = null
        panelView = null
    }

    // ---- Touch handling ----

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                touchStartX = event.rawX
                touchStartY = event.rawY
                touchStartParamX = layoutParams.x
                touchStartParamY = layoutParams.y
                touchStartTime = System.currentTimeMillis()

                // Long press timer
                mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchStartX
                val dy = event.rawY - touchStartY

                if (!isDragging && (dx * dx + dy * dy) > tapSlopPx * tapSlopPx) {
                    isDragging = true
                    mainHandler.removeCallbacks(longPressRunnable)
                }

                if (isDragging) {
                    layoutParams.x = (touchStartParamX + dx).toInt()
                    layoutParams.y = (touchStartParamY + dy).toInt()
                    wm.updateViewLayout(rootView, layoutParams)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)

                if (isDragging) {
                    // Snap to nearest horizontal edge
                    snapToEdge()
                    savePosition()
                } else {
                    // Tap
                    onEvent(SessionEvent.BUBBLE_TAP)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (isDragging) {
                    snapToEdge()
                    savePosition()
                }
                return true
            }
        }
        return false
    }

    private val longPressRunnable = Runnable {
        if (!isDragging) {
            onEvent(SessionEvent.BUBBLE_LONG_PRESS)
        }
    }

    private fun snapToEdge() {
        val display = wm.defaultDisplay
        val screenWidth = display.width
        val centerX = layoutParams.x + bubbleSizePx / 2

        val targetX = if (centerX < screenWidth / 2) {
            0 // Snap left
        } else {
            screenWidth - bubbleSizePx // Snap right
        }

        // Clamp Y to screen bounds
        val screenHeight = display.height
        layoutParams.y = layoutParams.y.coerceIn(0, screenHeight - bubbleSizePx)

        // Animate snap
        val startX = layoutParams.x
        val steps = 10
        for (i in 1..steps) {
            val fraction = i.toFloat() / steps
            mainHandler.postDelayed({
                layoutParams.x = (startX + (targetX - startX) * fraction).toInt()
                try { wm.updateViewLayout(rootView, layoutParams) } catch (_: Exception) {}
            }, (SNAP_ANIM_MS * fraction).toLong())
        }
    }

    private fun savePosition() {
        prefs.edit()
            .putInt(KEY_X, layoutParams.x)
            .putInt(KEY_Y, layoutParams.y)
            .apply()
    }

    // ---- State-driven UI updates ----

    fun expandPanel() {
        isExpanded = true
        panelView?.visibility = View.VISIBLE
        panelView?.showIdle()
        updatePanelSide()
    }

    fun collapsePanel() {
        isExpanded = false
        panelView?.visibility = View.GONE
        wolfFaceView?.setState(SessionState.IDLE)
        currentState = SessionState.IDLE
    }

    fun setRecording(recording: Boolean) {
        currentState = if (recording) SessionState.RECORDING else SessionState.IDLE
        wolfFaceView?.setState(currentState)
        panelView?.setRecording(recording)
    }

    fun setDraining(draining: Boolean) {
        currentState = SessionState.DRAINING
        wolfFaceView?.setState(currentState)
        panelView?.setDraining(draining)
    }

    fun showDelivered() {
        currentState = SessionState.DELIVERED
        wolfFaceView?.setState(currentState)
        panelView?.showDelivered()
    }

    fun showError() {
        currentState = SessionState.ERROR
        wolfFaceView?.setState(currentState)
        panelView?.showError()
    }

    fun pushRms(rms: Float) {
        panelView?.pushRms(rms)
    }

    /**
     * Position the panel on the side with more space (toward screen centre).
     */
    private fun updatePanelSide() {
        val display = wm.defaultDisplay
        val screenWidth = display.width
        val centerX = layoutParams.x + bubbleSizePx / 2
        val panelOnRight = centerX < screenWidth / 2

        panelView?.let { panel ->
            val lp = panel.layoutParams as FrameLayout.LayoutParams
            if (panelOnRight) {
                lp.gravity = Gravity.START
                lp.leftMargin = bubbleSizePx + (4 * density).toInt()
            } else {
                lp.gravity = Gravity.END
                lp.rightMargin = bubbleSizePx + (4 * density).toInt()
            }
            panel.layoutParams = lp
        }
    }
}
