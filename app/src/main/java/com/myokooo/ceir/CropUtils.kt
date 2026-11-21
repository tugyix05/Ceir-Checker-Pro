package com.myokooo.ceir

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

object CropUtils {
    // v5 scan band geometry (matches ScanBandView): wide-short white frame,
    // 88% wide, 112dp tall centered on the 320dp camera card.
    // ML Kit only sees inside the band — nothing outside is recognized.
    const val CROP_X = 0.06f    // ဘေးဘက် ၆% စီ
    const val CROP_Y = 0.325f   // (320-112)/2 / 320
    const val CROP_W = 0.88f    // အကျယ် ၈၈%
    const val CROP_H = 0.35f    // အမြင့် 112/320

    // Item 11 — adaptive scaling: upscale SMALL crops only, cap long edge ~1600px.
    // (Fixed 4x made close-up text oversized for ML Kit; fixed 1.5x was a stopgap.)
    private const val TARGET_LONG_EDGE = 1400
    private const val MAX_LONG_EDGE = 1600
    private const val MAX_UPSCALE = 2.0f
    private const val CONTRAST_BOOST = 1.3f

    /**
     * Returns cropped+enhanced bitmap, or null on failure.
     * Caller must recycle the returned bitmap when done.
     * NOTE: source bitmap is NOT recycled here — caller owns it.
     */
    fun cropBitmap(source: Bitmap): Bitmap? {
        val w = source.width
        val h = source.height

        val left = (w * CROP_X).toInt()
        val top = (h * CROP_Y).toInt()
        val cw = (w * CROP_W).toInt()
        val ch = (h * CROP_H).toInt()

        if (left + cw > w || top + ch > h || cw <= 0 || ch <= 0) return null

        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            cropped = Bitmap.createBitmap(source, left, top, cw, ch)
            // Item 11 — adaptive scale factor from the crop's long edge
            val longEdge = maxOf(cropped.width, cropped.height)
            val factor = when {
                longEdge >= MAX_LONG_EDGE -> MAX_LONG_EDGE.toFloat() / longEdge
                longEdge < TARGET_LONG_EDGE ->
                    (TARGET_LONG_EDGE.toFloat() / longEdge).coerceAtMost(MAX_UPSCALE)
                else -> 1f
            }
            scaled = if (factor == 1f) {
                val s = cropped
                cropped = null
                s
            } else {
                val s = Bitmap.createScaledBitmap(
                    cropped,
                    (cropped.width * factor).toInt().coerceAtLeast(1),
                    (cropped.height * factor).toInt().coerceAtLeast(1),
                    true
                )
                cropped.recycle()
                cropped = null
                s
            }
            val out = enhanceContrast(scaled)
            scaled = null
            out
        } catch (e: Exception) {
            null
        } finally {
            // FIX: leak — intermediate bitmaps always recycled on failure path
            try { cropped?.recycle() } catch (_: Exception) {}
            try { scaled?.recycle() } catch (_: Exception) {}
        }
    }

    private fun enhanceContrast(source: Bitmap): Bitmap {
        return try {
            val result = Bitmap.createBitmap(source.width, source.height, source.config ?: Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)

            val cm = ColorMatrix()
            val scale = CONTRAST_BOOST
            val translate = (-0.5f * scale + 0.5f) * 255f

            cm.set(floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            ))

            val paint = Paint()
            paint.colorFilter = ColorMatrixColorFilter(cm)
            canvas.drawBitmap(source, 0f, 0f, paint)
            source.recycle()

            result
        } catch (e: Exception) {
            source
        }
    }
}
