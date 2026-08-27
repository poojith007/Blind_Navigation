package com.blindnav.app.ml

import android.graphics.RectF

enum class Position {
    LEFT,
    CENTER,
    RIGHT
}

enum class ThreatLevel {
    SAFE,
    CAUTION,
    DANGER
}

data class DetectedObject(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
    val distanceMeters: Float,
    val position: Position,
    val threatLevel: ThreatLevel,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toTtsPrompt(): String {
        val posString = when (position) {
            Position.LEFT -> "on your left"
            Position.RIGHT -> "on your right"
            Position.CENTER -> "ahead"
        }
        
        val prefix = if (threatLevel == ThreatLevel.DANGER) "Warning! " else ""
        return "$prefix${label.replaceFirstChar { it.uppercase() }} $posString, ${String.format("%.1f", distanceMeters)} meters."
    }
}
