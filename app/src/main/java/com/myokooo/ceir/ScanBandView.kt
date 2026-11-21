package com.myokooo.ceir

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * v5 scan band: wide-short white rounded frame (88% width x 112dp, centered),
 * dimmed surroundings while live, white laser line sweeping up-down while scanning.
 *
 * Geometry matches CropUtils band constants — ML Kit only sees inside the band.
 * Replaces the old corner-bracket ViewfinderView.
 */
class ScanBandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val BAND_W_FRAC = 0.88f
        const val BAND_H_DP = 112f
    }

    private val density = resources.displayMetrics.density
    private val bandH = BAND_H_DP * density

    private val dimPaint = Paint().apply { color = 0x8C04070E.toInt() } // ~55% dark navy
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 2.5f * density
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        alpha = 60
        strokeWidth = 10f * density
    }

    /** Dim everything outside the band (live states). */
    var dimmed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Animate the laser line up-down inside the band. */
    var scanning: Boolean = false
        set(value) {
            field = value
            if (value) startAnim() else stopAnim()
            invalidate()
        }

    private var laserFrac = 0f
    private var animator: ValueAnimator? = null

    private fun bandRect(): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        val bw = w * BAND_W_FRAC
        val left = (w - bw) / 2f
        val top = (h - bandH) / 2f
        return RectF(left, top, left + bw, top + bandH)
    }

    private fun startAnim() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { laserFrac = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopAnim() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = bandRect()
        if (r.width() <= 0f) return
        val radius = 26f * density

        if (dimmed) {
            canvas.drawRect(0f, 0f, width.toFloat(), r.top, dimPaint)
            canvas.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(0f, r.top, r.left, r.bottom, dimPaint)
            canvas.drawRect(r.right, r.top, width.toFloat(), r.bottom, dimPaint)
        }

        canvas.drawRoundRect(r, radius, radius, framePaint)

        if (scanning) {
            val y = r.top + 10f * density + laserFrac * (r.height() - 20f * density)
            val inset = 16f * density
            canvas.drawLine(r.left + inset, y, r.right - inset, y, glowPaint)
            canvas.drawLine(r.left + inset, y, r.right - inset, y, laserPaint)
        }
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }
}
