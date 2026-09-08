package com.voxwolf.dictation.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * SPEC §8.3 — Waveform visualiser.
 *
 * 30 fps via Choreographer callback.
 * 64-slot RMS ring buffer.
 * Three polylines: primary (green), secondary (attenuated), tertiary (dim).
 * DRAINING mode: lemniscate animation.
 * IDLE/ERROR mode: no animation drawn.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val RING_SIZE = 64
        private const val FRAME_INTERVAL_NS = 33_333_333L // ~30fps
        private const val LEMNISCATE_PERIOD_MS = 2000L
    }

    enum class Mode {
        IDLE,
        RECORDING,
        DRAINING,
        ERROR
    }

    // RMS ring buffer
    private val rmsRing = FloatArray(RING_SIZE)
    private var ringHead = 0

    // Paints
    private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#804CAF50") // 50% alpha green
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val tertiaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#404CAF50") // 25% alpha green
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val lemniscatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFC107")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val path = Path()
    private var mode = Mode.IDLE
    private var isRunning = false
    private var lemniscateStartTime = 0L

    private val choreographerCallback = object : Choreographer.FrameCallback {
        private var lastFrameNanos = 0L

        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return

            if (frameTimeNanos - lastFrameNanos >= FRAME_INTERVAL_NS) {
                lastFrameNanos = frameTimeNanos
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun setMode(newMode: Mode) {
        mode = newMode
        when (newMode) {
            Mode.RECORDING -> startAnimation()
            Mode.DRAINING -> {
                lemniscateStartTime = System.currentTimeMillis()
                startAnimation()
            }
            Mode.IDLE, Mode.ERROR -> stopAnimation()
        }
        invalidate()
    }

    fun pushRms(rms: Float) {
        rmsRing[ringHead] = rms.coerceIn(0f, 1f)
        ringHead = (ringHead + 1) % RING_SIZE
    }

    private fun startAnimation() {
        if (isRunning) return
        isRunning = true
        Choreographer.getInstance().postFrameCallback(choreographerCallback)
    }

    private fun stopAnimation() {
        isRunning = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        when (mode) {
            Mode.RECORDING -> drawWaveform(canvas)
            Mode.DRAINING -> drawLemniscate(canvas)
            Mode.IDLE, Mode.ERROR -> { /* No animation */ }
        }
    }

    private fun drawWaveform(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val midY = h / 2f
        val slotWidth = w / RING_SIZE

        // Draw three polylines with decreasing attenuation
        drawPolyline(canvas, midY, slotWidth, 1.0f, h * 0.4f, primaryPaint)
        drawPolyline(canvas, midY, slotWidth, 0.6f, h * 0.3f, secondaryPaint)
        drawPolyline(canvas, midY, slotWidth, 0.3f, h * 0.2f, tertiaryPaint)
    }

    private fun drawPolyline(
        canvas: Canvas,
        midY: Float,
        slotWidth: Float,
        scale: Float,
        maxAmplitude: Float,
        paint: Paint
    ) {
        path.reset()
        var first = true

        for (i in 0 until RING_SIZE) {
            val ringIdx = (ringHead + i) % RING_SIZE
            val x = i * slotWidth
            val amplitude = rmsRing[ringIdx] * scale * maxAmplitude
            val y = midY - amplitude

            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
        }

        canvas.drawPath(path, paint)

        // Mirror below midline
        path.reset()
        first = true
        for (i in 0 until RING_SIZE) {
            val ringIdx = (ringHead + i) % RING_SIZE
            val x = i * slotWidth
            val amplitude = rmsRing[ringIdx] * scale * maxAmplitude
            val y = midY + amplitude

            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, paint)
    }

    /**
     * Lemniscate (∞ / figure-eight) animation during DRAINING state.
     */
    private fun drawLemniscate(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val a = w * 0.35f // half-width of the lemniscate

        val elapsed = System.currentTimeMillis() - lemniscateStartTime
        val progress = (elapsed % LEMNISCATE_PERIOD_MS).toFloat() / LEMNISCATE_PERIOD_MS
        val drawUpTo = progress * (2.0 * Math.PI).toFloat()

        path.reset()
        val steps = 100
        var first = true

        for (i in 0..steps) {
            val t = (i.toFloat() / steps) * drawUpTo
            val denom = 1 + sin(t.toDouble()) * sin(t.toDouble())
            val x = (a * cos(t.toDouble()) / denom).toFloat() + cx
            val y = (a * sin(t.toDouble()) * cos(t.toDouble()) / denom).toFloat() + cy

            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
        }

        canvas.drawPath(path, lemniscatePaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }
}
