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

    var isDemoModeEnabled: Boolean = false
        set(value) {
            field = value
            postInvalidate()
        }

    private val corridorStrokePaint = Paint().apply {
        color = Color.parseColor("#4D00E5FF") // 30% translucent cyan
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val corridorFillPaint = Paint().apply {
        color = Color.parseColor("#1200E5FF") // 7% translucent cyan
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun updateDetections(objects: List<DetectedObject>) {
        this.detectedObjects = objects
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // 1. Draw Safe Walking Corridor Overlay (assistive light pathway)
        drawWalkingCorridor(canvas, viewWidth, viewHeight)

        // 2. In Normal Mode, do not clutter the screen with dozens of bounding boxes.
        // Only draw subtle indicator for immediate DANGER obstacles in the path.
        if (!isDemoModeEnabled) {
            val criticalThreats = detectedObjects.filter { it.threatLevel == ThreatLevel.DANGER && it.isInCorridor }
            for (obj in criticalThreats) {
                val box = obj.boundingBox
                val scaledRect = RectF(
                    box.left * viewWidth,
                    box.top * viewHeight,
                    box.right * viewWidth,
                    box.bottom * viewHeight
                )
                canvas.drawRoundRect(scaledRect, 18f, 18f, dangerFillPaint)
                canvas.drawRoundRect(scaledRect, 18f, 18f, dangerStrokePaint)
            }
            return
        }

        // 3. In Demo / Developer Mode, render full AI bounding boxes, labels, distance, and tags for examiner demonstration
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

            // Draw semi-transparent rounded box fill & neon stroke border
            canvas.drawRoundRect(scaledRect, 18f, 18f, fillPaint)
            canvas.drawRoundRect(scaledRect, 18f, 18f, strokePaint)

            val corridorTag = if (obj.isInCorridor) " [PATH]" else ""
            val text = "${obj.label.uppercase()} ${String.format("%.1f", obj.distanceMeters)}m (${(obj.confidence * 100).toInt()}%)$corridorTag"
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

    private fun drawWalkingCorridor(canvas: Canvas, viewWidth: Float, viewHeight: Float) {
        val path = android.graphics.Path()
        val bottomY = viewHeight
        val topY = viewHeight * 0.40f

        val bottomWidthHalf = viewWidth * 0.28f // ~56% width at bottom
        val topWidthHalf = viewWidth * 0.14f    // ~28% width at projected horizon
        val centerX = viewWidth * 0.50f

        val p1x = centerX - bottomWidthHalf
        val p2x = centerX - topWidthHalf
        val p3x = centerX + topWidthHalf
        val p4x = centerX + bottomWidthHalf

        path.moveTo(p1x, bottomY)
        path.lineTo(p2x, topY)
        path.lineTo(p3x, topY)
        path.lineTo(p4x, bottomY)
        path.close()

        canvas.drawPath(path, corridorFillPaint)
        canvas.drawLine(p1x, bottomY, p2x, topY, corridorStrokePaint)
        canvas.drawLine(p4x, bottomY, p3x, topY, corridorStrokePaint)
    }
}
