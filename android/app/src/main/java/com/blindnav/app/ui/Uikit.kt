package com.blindnav.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

/**
 * Shared sizing & styling helpers so every screen uses real dp instead of raw pixels.
 * Fixes cards/borders/corners looking hairline-thin or cramped on high-density phones.
 */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
fun Context.dpf(value: Int): Float = value * resources.displayMetrics.density
fun View.dp(value: Int): Int = context.dp(value)

fun pill(
    context: Context,
    bgColor: Int,
    strokeColor: Int = Color.TRANSPARENT,
    strokeWidthDp: Int = 0,
    radiusDp: Int = 16
): GradientDrawable = GradientDrawable().apply {
    setColor(bgColor)
    cornerRadius = context.dpf(radiusDp)
    if (strokeWidthDp > 0) setStroke(context.dp(strokeWidthDp), strokeColor)
}

/**
 * Single palette used everywhere so colors are consistent across every screen and dialog.
 */
object Palette {
    val bgDark = Color.parseColor("#0D1117")
    val cardBg = Color.parseColor("#E6161B22")
    val cardBorder = Color.parseColor("#2A3340")

    val danger = Color.parseColor("#FF1744")
    val caution = Color.parseColor("#FFD600")
    val safe = Color.parseColor("#00E676")
    val info = Color.parseColor("#00E5FF")
    val muted = Color.parseColor("#78909C")
    val white = Color.WHITE
}