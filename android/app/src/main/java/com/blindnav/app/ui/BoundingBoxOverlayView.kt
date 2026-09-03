package com.blindnav.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.blindnav.app.ml.DetectedObject
import com.blindnav.app.ml.ThreatLevel

class BoundingBoxOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detectedObjects = emptyList<DetectedObject>()

    private val dangerStrokePaint = Paint().apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val dangerFillPaint = Paint().apply {
        color = Color.parseColor("#33FF1744")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val cautionStrokePaint = Paint().apply {
        color = Color.parseColor("#FFD600")
        style = Paint.Style.STROKE
        strokeWidth = 7f
        isAntiAlias = true
    }

    private val cautionFillPaint = Paint().apply {
        color = Color.parseColor("#26FFD600")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val safeStrokePaint = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val safeFillPaint = Paint().apply {
        color = Color.parseColor("#1A00E676")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val badgeBackgroundPaint = Paint().apply {
        color = Color.parseColor("#E60D1117")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val badgeBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 38f
        isAntiAlias = true
        isFakeBoldText = true
    }

    fun updateDetections(objects: List<DetectedObject>) {
        this.detectedObjects = objects
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        for (obj in detectedObjects) {
            val box = obj.boundingBox
            val scaledRect = RectF(
                box.left * viewWidth,
                box.top * viewHeight,
                box.right * viewWidth,
                box.bottom * viewHeight
            )

            val (strokePaint, fillPaint, badgeColor) = when (obj.threatLevel) {
                ThreatLevel.DANGER -> Triple(dangerStrokePaint, dangerFillPaint, Color.parseColor("#FF1744"))
                ThreatLevel.CAUTION -> Triple(cautionStrokePaint, cautionFillPaint, Color.parseColor("#FFD600"))
                ThreatLevel.SAFE -> Triple(safeStrokePaint, safeFillPaint, Color.parseColor("#00E676"))
            }

            // Draw semi-transparent rounded box fill
            canvas.drawRoundRect(scaledRect, 18f, 18f, fillPaint)
            // Draw neon stroke border
            canvas.drawRoundRect(scaledRect, 18f, 18f, strokePaint)

            val text = "${obj.label.uppercase()}  ${String.format("%.1f", obj.distanceMeters)}m"
            val textWidth = textPaint.measureText(text)
            val textHeight = textPaint.textSize

            val badgeRect = RectF(
                scaledRect.left,
                (scaledRect.top - textHeight - 24f).coerceAtLeast(8f),
                scaledRect.left + textWidth + 32f,
                (scaledRect.top).coerceAtLeast(textHeight + 32f)
            )

            // Draw rounded badge background with accent border
            badgeBorderPaint.color = badgeColor
            canvas.drawRoundRect(badgeRect, 12f, 12f, badgeBackgroundPaint)
            canvas.drawRoundRect(badgeRect, 12f, 12f, badgeBorderPaint)

            // Draw text label
            canvas.drawText(text, badgeRect.left + 16f, badgeRect.bottom - 12f, textPaint)
        }
    }
}
