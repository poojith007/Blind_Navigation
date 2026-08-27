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

    private val dangerPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 10f
        isAntiAlias = true
    }

    private val cautionPaint = Paint().apply {
        color = Color.parseColor("#FFA500") // Orange
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val safePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        alpha = 180
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
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

            val boxPaint = when (obj.threatLevel) {
                ThreatLevel.DANGER -> dangerPaint
                ThreatLevel.CAUTION -> cautionPaint
                ThreatLevel.SAFE -> safePaint
            }

            canvas.drawRect(scaledRect, boxPaint)

            val text = "${obj.label.uppercase()} | ${String.format("%.1f", obj.distanceMeters)}m"
            val textWidth = textPaint.measureText(text)
            val textHeight = textPaint.textSize

            val textRect = RectF(
                scaledRect.left,
                (scaledRect.top - textHeight - 16f).coerceAtLeast(0f),
                scaledRect.left + textWidth + 24f,
                (scaledRect.top).coerceAtLeast(textHeight + 16f)
            )

            canvas.drawRect(textRect, textBackgroundPaint)
            canvas.drawText(text, textRect.left + 12f, textRect.bottom - 12f, textPaint)
        }
    }
}
