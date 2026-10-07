package com.blindnav.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.blindnav.app.ml.DetectedObject
import com.blindnav.app.ml.ThreatLevel

/**
 * Draws AI detection overlays on top of the camera preview.
 *
 * Change from the previous version: the walking-corridor trapezoid now only
 * draws in Demo Mode. It's a visual aid for sighted examiners watching the
 * screen, not something the (blind/low-vision) user relies on — audio and
 * haptic feedback carry the real guidance — so it no longer sits permanently
 * on top of the camera feed.
 */
class BoundingBoxOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detectedObjects = emptyList<DetectedObject>()

    private val dangerStroke = Paint().apply {
        color = Color.parseColor("#FF1744"); style = Paint.Style.STROKE
        strokeWidth = dp(3).toFloat(); isAntiAlias = true
    }
    private val dangerFill = Paint().apply {
        color = Color.parseColor("#33FF1744"); style = Paint.Style.FILL; isAntiAlias = true
    }
    private val cautionStroke = Paint().apply {
        color = Color.parseColor("#FFD600"); style = Paint.Style.STROKE
        strokeWidth = dp(3).toFloat(); isAntiAlias = true
    }
    private val cautionFill = Paint().apply {
        color = Color.parseColor("#26FFD600"); style = Paint.Style.FILL; isAntiAlias = true
    }
    private val safeStroke = Paint().apply {
        color = Color.parseColor("#00E676"); style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat(); isAntiAlias = true
    }
    private val safeFill = Paint().apply {
        color = Color.parseColor("#1A00E676"); style = Paint.Style.FILL; isAntiAlias = true
    }
    private val badgeBg = Paint().apply {
        color = Color.parseColor("#E60D1117"); style = Paint.Style.FILL; isAntiAlias = true
    }
    private val badgeBorder = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat(); isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = dp(13).toFloat(); isAntiAlias = true; isFakeBoldText = true
    }
    private val corridorStroke = Paint().apply {
        color = Color.parseColor("#4D00E5FF"); style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat(); isAntiAlias = true
    }
    private val corridorFill = Paint().apply {
        color = Color.parseColor("#1200E5FF"); style = Paint.Style.FILL; isAntiAlias = true
    }

    var isDemoModeEnabled: Boolean = false
        set(value) { field = value; postInvalidate() }

    fun updateDetections(objects: List<DetectedObject>) {
        this.detectedObjects = objects
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Corridor guide is a demo/examiner aid only — not shown during normal use.
        if (isDemoModeEnabled) {
            drawWalkingCorridor(canvas, w, h)
        }

        if (!isDemoModeEnabled) {
            // Normal use: only a subtle box around an immediate in-path danger, nothing else.
            val criticalThreats = detectedObjects.filter { it.threatLevel == ThreatLevel.DANGER && it.isInCorridor }
            for (obj in criticalThreats) {
                val rect = scaledRect(obj.boundingBox, w, h)
                canvas.drawRoundRect(rect, dp(12).toFloat(), dp(12).toFloat(), dangerFill)
                canvas.drawRoundRect(rect, dp(12).toFloat(), dp(12).toFloat(), dangerStroke)
            }
            return
        }

        // Demo mode: full bounding boxes + labels for examiner demonstration.
        for (obj in detectedObjects) {
            val rect = scaledRect(obj.boundingBox, w, h)
            val (stroke, fill, badgeColor) = when (obj.threatLevel) {
                ThreatLevel.DANGER -> Triple(dangerStroke, dangerFill, Color.parseColor("#FF1744"))
                ThreatLevel.CAUTION -> Triple(cautionStroke, cautionFill, Color.parseColor("#FFD600"))
                ThreatLevel.SAFE -> Triple(safeStroke, safeFill, Color.parseColor("#00E676"))
            }
            canvas.drawRoundRect(rect, dp(12).toFloat(), dp(12).toFloat(), fill)
            canvas.drawRoundRect(rect, dp(12).toFloat(), dp(12).toFloat(), stroke)

            val corridorTag = if (obj.isInCorridor) " [PATH]" else ""
            val text = "${obj.label.uppercase()} ${String.format("%.1f", obj.distanceMeters)}m (${(obj.confidence * 100).toInt()}%)$corridorTag"
            val textWidth = textPaint.measureText(text)
            val textHeight = textPaint.textSize

            val badgeRect = RectF(
                rect.left,
                (rect.top - textHeight - dp(10)).coerceAtLeast(dp(4).toFloat()),
                rect.left + textWidth + dp(12),
                rect.top.coerceAtLeast(textHeight + dp(12))
            )
            badgeBorder.color = badgeColor
            canvas.drawRoundRect(badgeRect, dp(8).toFloat(), dp(8).toFloat(), badgeBg)
            canvas.drawRoundRect(badgeRect, dp(8).toFloat(), dp(8).toFloat(), badgeBorder)
            canvas.drawText(text, badgeRect.left + dp(6), badgeRect.bottom - dp(5), textPaint)
        }
    }

    private fun scaledRect(box: RectF, w: Float, h: Float) = RectF(
        box.left * w, box.top * h, box.right * w, box.bottom * h
    )

    private fun drawWalkingCorridor(canvas: Canvas, w: Float, h: Float) {
        val path = Path()
        val bottomY = h
        val topY = h * 0.40f
        val bottomHalf = w * 0.28f
        val topHalf = w * 0.14f
        val cx = w * 0.50f

        val p1x = cx - bottomHalf; val p2x = cx - topHalf
        val p3x = cx + topHalf; val p4x = cx + bottomHalf

        path.moveTo(p1x, bottomY); path.lineTo(p2x, topY)
        path.lineTo(p3x, topY); path.lineTo(p4x, bottomY); path.close()

        canvas.drawPath(path, corridorFill)
        canvas.drawLine(p1x, bottomY, p2x, topY, corridorStroke)
        canvas.drawLine(p4x, bottomY, p3x, topY, corridorStroke)
    }
}