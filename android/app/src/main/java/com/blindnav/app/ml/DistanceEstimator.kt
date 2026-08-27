package com.blindnav.app.ml

import android.graphics.RectF

object DistanceEstimator {

    // Approximate real-world heights (meters) for objects
    private val REAL_WORLD_HEIGHTS = mapOf(
        "person" to 1.70f,
        "chair" to 0.90f,
        "couch" to 0.85f,
        "car" to 1.50f,
        "bus" to 3.20f,
        "truck" to 3.00f,
        "bicycle" to 1.00f,
        "motorcycle" to 1.10f,
        "bottle" to 0.25f,
        "tv" to 0.60f,
        "dining table" to 0.75f,
        "door" to 2.00f,
        "stairs" to 1.50f,
        "stop sign" to 1.00f,
        "traffic light" to 1.20f
    )

    // Default height for unmapped objects
    private const val DEFAULT_HEIGHT = 1.00f

    // Calibrated focal length constant for mobile camera field-of-view (~60 degree FOV)
    private const val FOCAL_CONSTANT = 0.85f

    fun estimateDistance(label: String, boundingBox: RectF): Float {
        val heightFraction = boundingBox.height()
        if (heightFraction <= 0.01f) return 99.0f

        val realHeight = REAL_WORLD_HEIGHTS[label.lowercase()] ?: DEFAULT_HEIGHT
        val distance = (FOCAL_CONSTANT * realHeight) / heightFraction

        return Math.round(distance * 10.0f) / 10.0f
    }

    fun determinePosition(boundingBox: RectF): Position {
        val centerX = boundingBox.centerX()
        return when {
            centerX < 0.35f -> Position.LEFT
            centerX > 0.65f -> Position.RIGHT
            else -> Position.CENTER
        }
    }

    fun determineThreatLevel(distanceMeters: Float, label: String): ThreatLevel {
        val isHighRisk = label.lowercase() in listOf("car", "bus", "truck", "motorcycle", "stairs", "fire hydrant")

        return when {
            distanceMeters <= 1.2f || (isHighRisk && distanceMeters <= 2.2f) -> ThreatLevel.DANGER
            distanceMeters <= 2.5f -> ThreatLevel.CAUTION
            else -> ThreatLevel.SAFE
        }
    }
}
