package com.voxwolf.dictation.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.voxwolf.dictation.R
import com.voxwolf.dictation.telemetry.Telemetry

/**
 * SPEC §8.2 — Expanded panel.
 *
 * Contains: red cross (cancel), waveform view, orange mic button.
 * Rounded dark background. Panel states: IDLE, RECORDING, DRAINING, DELIVERED, ERROR.
 */
class PanelView @JvmOverloads constructor(
    context: Context,
    private val telemetry: Telemetry? = null,
    private val onMicPressed: (() -> Unit)? = null,
    private val onCancelPressed: (() -> Unit)? = null,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val PANEL_WIDTH_DP = 200
        private const val PANEL_HEIGHT_DP = 160
        private const val CORNER_RADIUS_DP = 16
        private const val MIC_BUTTON_SIZE_DP = 48
        private const val CANCEL_BUTTON_SIZE_DP = 32
    }

    private val density = context.resources.displayMetrics.density

    private val waveformView: WaveformView
    private val micButton: ImageButton
    private val cancelButton: ImageButton
    private val statusText: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL

        val widthPx = (PANEL_WIDTH_DP * density).toInt()
        val heightPx = (PANEL_HEIGHT_DP * density).toInt()
        layoutParams = FrameLayout.LayoutParams(widthPx, heightPx).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        // Dark rounded background
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#E0212121"))
            cornerRadius = CORNER_RADIUS_DP * density
        }
        background = bg
        val pad = (8 * density).toInt()
        setPadding(pad, pad, pad, pad)

        // Top row: cancel button (red cross)
        cancelButton = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#F44336"))
            setBackgroundColor(Color.TRANSPARENT)
            val size = (CANCEL_BUTTON_SIZE_DP * density).toInt()
            layoutParams = LayoutParams(size, size).apply {
                gravity = Gravity.END
            }
            contentDescription = "Cancel"
            setOnClickListener { onCancelPressed?.invoke() }
        }
        addView(cancelButton)

        // Waveform view (centre)
        waveformView = WaveformView(context)
        val wfLp = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            val margin = (4 * density).toInt()
            setMargins(margin, margin, margin, margin)
        }
        addView(waveformView, wfLp)

        // Status text (shown in DRAINING, DELIVERED, ERROR)
        statusText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        addView(statusText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Bottom: mic button (orange circle)
        micButton = ImageButton(context).apply {
            val size = (MIC_BUTTON_SIZE_DP * density).toInt()
            layoutParams = LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (4 * density).toInt()
            }
            val micBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF9800"))
            }
            background = micBg
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
            contentDescription = "Microphone"
            setOnClickListener { onMicPressed?.invoke() }
        }
        addView(micButton)
    }

    fun showIdle() {
        micButton.visibility = View.VISIBLE
        micButton.isEnabled = true
        cancelButton.visibility = View.VISIBLE
        waveformView.visibility = View.VISIBLE
        statusText.visibility = View.GONE
        waveformView.setMode(WaveformView.Mode.IDLE)
    }

    fun setRecording(recording: Boolean) {
        if (recording) {
            micButton.visibility = View.VISIBLE
            micButton.isEnabled = true
            cancelButton.visibility = View.VISIBLE
            waveformView.visibility = View.VISIBLE
            statusText.visibility = View.GONE
            waveformView.setMode(WaveformView.Mode.RECORDING)

            // Change mic button to "stop" appearance (red)
            val stopBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F44336"))
            }
            micButton.background = stopBg
        } else {
            showIdle()
        }
    }

    fun setDraining(draining: Boolean) {
        if (draining) {
            micButton.visibility = View.GONE
            cancelButton.visibility = View.VISIBLE
            waveformView.visibility = View.VISIBLE
            waveformView.setMode(WaveformView.Mode.DRAINING)
            statusText.visibility = View.VISIBLE
            statusText.text = "Processing…"
            statusText.setTextColor(Color.parseColor("#FFC107"))
        }
    }

    fun showDelivered() {
        micButton.visibility = View.GONE
        cancelButton.visibility = View.GONE
        waveformView.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = "✓ Inserted"
        statusText.setTextColor(Color.parseColor("#4CAF50"))
    }

    fun showError() {
        micButton.visibility = View.GONE
        cancelButton.visibility = View.GONE
        waveformView.visibility = View.GONE
        statusText.visibility = View.VISIBLE
        statusText.text = "Error — tap wolf to dismiss"
        statusText.setTextColor(Color.parseColor("#F44336"))
    }

    fun pushRms(rms: Float) {
        waveformView.pushRms(rms)
    }
}
