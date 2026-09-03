package com.blindnav.app.ml

import android.graphics.RectF
import java.util.Locale

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
    /**
     * Generates a clear, actionable TTS prompt with evasive navigation guidance
     * telling the user which way to step or avoid.
     */
    fun toTtsPrompt(): String {
        val distFormatted = String.format(Locale.US, "%.1f", distanceMeters)
        val capitalizedLabel = label.replaceFirstChar { it.uppercase() }

        // Critical proximity emergency (< 0.8 meters)
        if (distanceMeters <= 0.8f && position == Position.CENTER) {
            return "Stop! $capitalizedLabel right in front of you at $distFormatted meters. Please halt and step back."
        }

        // Actionable directional guidance based on obstacle zone and threat level
        val (posString, guidance) = when (position) {
            Position.CENTER -> {
                when (threatLevel) {
                    ThreatLevel.DANGER -> "directly ahead" to "Stop! Move to your right to avoid."
                    ThreatLevel.CAUTION -> "ahead" to "Veer right to bypass."
                    ThreatLevel.SAFE -> "ahead" to "Path clear."
                }
            }
            Position.LEFT -> {
                when (threatLevel) {
                    ThreatLevel.DANGER -> "close on your left" to "Step right."
                    ThreatLevel.CAUTION -> "on your left" to "Keep slightly to your right."
                    ThreatLevel.SAFE -> "on your left" to ""
                }
            }
            Position.RIGHT -> {
                when (threatLevel) {
                    ThreatLevel.DANGER -> "close on your right" to "Step left."
                    ThreatLevel.CAUTION -> "on your right" to "Keep slightly to your left."
                    ThreatLevel.SAFE -> "on your right" to ""
                }
            }
        }

        val prefix = if (threatLevel == ThreatLevel.DANGER) "Warning! " else ""
        val guidanceSuffix = if (guidance.isNotBlank()) " $guidance" else ""
        return "$prefix$capitalizedLabel $posString, $distFormatted meters.$guidanceSuffix"
    }
}
