package com.myokooo.ceir

import android.content.Context
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/**
 * Item 8 — auto-adaptive density: 9 history cards fit on ANY screen.
 * Card height = list height ÷ 9; fonts/padding scale proportionally
 * against the 72dp design baseline. Scanner uses the same factor
 * derived from screen height so both screens match.
 */
object DensityUtils {

    private const val BASE_CARD_DP = 72f
    private const val REF_SCREEN_DP = 740f
    private const val MIN_F = 0.7f
    private const val MAX_F = 1.15f

    /** Factor for the history list, from the measured per-card height. */
    fun cardFactor(cardHeightPx: Float, dm: DisplayMetrics): Float {
        if (cardHeightPx <= 0) return 1f
        return (cardHeightPx / (BASE_CARD_DP * dm.density)).coerceIn(MIN_F, MAX_F)
    }

    /** Factor for fixed screens (scanner), from screen height. */
    fun screenFactor(context: Context): Float {
        val dm = context.resources.displayMetrics
        val screenDp = dm.heightPixels / dm.density
        return (screenDp / REF_SCREEN_DP).coerceIn(MIN_F, MAX_F)
    }

    fun scaledSp(tv: TextView, baseSp: Float, f: Float) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp * f)
    }

    fun scaledPadding(v: View, dm: DisplayMetrics, lDp: Float, tDp: Float, rDp: Float, bDp: Float, f: Float) {
        val d = dm.density
        v.setPadding(
            (lDp * d * f).toInt(), (tDp * d * f).toInt(),
            (rDp * d * f).toInt(), (bDp * d * f).toInt()
        )
    }
}
