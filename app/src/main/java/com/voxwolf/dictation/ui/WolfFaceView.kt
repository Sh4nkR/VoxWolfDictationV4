package com.voxwolf.dictation.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.voxwolf.dictation.R
import com.voxwolf.dictation.state.SessionState

/**
 * SPEC §8.1 — Wolf face vector bubble.
 *
 * Layers:
 *   - wolf_face.xml: full wolf face vector drawable (tinted per state)
 *   - wolf_eyes.xml: eye layer, animated with blink during RECORDING
 *
 * State colours:
 *   IDLE      → #B0BEC5 (blue-grey)
 *   EXPANDED  → #FFFFFF (white)
 *   RECORDING → #4CAF50 (green), blink animation active
 *   DRAINING  → #FFC107 (amber)
 *   DELIVERED → #4CAF50 (green)
 *   ERROR     → #F44336 (red), solid (no animation)
 */
class WolfFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val faceView: ImageView
    private val eyesView: ImageView
    private var blinkAnimator: ObjectAnimator? = null

    init {
        // Wolf face layer
        faceView = ImageView(context).apply {
            setImageResource(R.drawable.wolf_face)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        addView(faceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Eyes layer (for blink animation)
        eyesView = ImageView(context).apply {
            setImageResource(R.drawable.wolf_eyes)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        addView(eyesView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Start in idle
        setState(SessionState.IDLE)
    }

    fun setState(state: SessionState) {
        val color = stateColor(state)
        faceView.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        eyesView.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

        // Blink animation only during RECORDING
        if (state == SessionState.RECORDING) {
            startBlinkAnimation()
        } else {
            stopBlinkAnimation()
            eyesView.alpha = 1f
        }
    }

    private fun stateColor(state: SessionState): Int = when (state) {
        SessionState.IDLE -> Color.parseColor("#B0BEC5")
        SessionState.EXPANDED -> Color.WHITE
        SessionState.RECORDING -> Color.parseColor("#4CAF50")
        SessionState.DRAINING -> Color.parseColor("#FFC107")
        SessionState.INSERTING -> Color.parseColor("#FFC107")
        SessionState.DELIVERED -> Color.parseColor("#4CAF50")
        SessionState.CANCELLED -> Color.parseColor("#B0BEC5")
        SessionState.ERROR -> Color.parseColor("#F44336")
    }

    private fun startBlinkAnimation() {
        if (blinkAnimator != null) return

        blinkAnimator = ObjectAnimator.ofFloat(eyesView, "alpha", 1f, 0f, 1f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopBlinkAnimation() {
        blinkAnimator?.cancel()
        blinkAnimator = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopBlinkAnimation()
    }
}
