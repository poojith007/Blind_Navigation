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

    fun estimateDistance(label: String, heightFraction: Float): Float {
        if (heightFraction <= 0.01f) return 99.0f

        val realHeight = REAL_WORLD_HEIGHTS[label.lowercase()] ?: DEFAULT_HEIGHT
        val distance = (FOCAL_CONSTANT * realHeight) / heightFraction

        return Math.round(distance * 10.0f) / 10.0f
    }

    fun estimateDistance(label: String, boundingBox: RectF): Float {
        val heightFraction = Math.abs(boundingBox.bottom - boundingBox.top)
        return estimateDistance(label, heightFraction)
    }

    fun determinePosition(centerX: Float): Position {
        return when {
            centerX < 0.35f -> Position.LEFT
            centerX > 0.65f -> Position.RIGHT
            else -> Position.CENTER
        }
    }

    fun determinePosition(boundingBox: RectF): Position {
        val centerX = (boundingBox.left + boundingBox.right) / 2.0f
        return determinePosition(centerX)
    }

    fun determineThreatLevel(distanceMeters: Float, label: String): ThreatLevel {
        val isHighRisk = label.lowercase() in listOf("car", "bus", "truck", "motorcycle", "stairs", "fire hydrant")

        return when {
            distanceMeters <= 1.2f || (isHighRisk && distanceMeters <= 2.2f) -> ThreatLevel.DANGER
            distanceMeters <= 2.5f -> ThreatLevel.CAUTION
            else -> ThreatLevel.SAFE
        }
    }

    /**
     * Calculates the horizontal boundaries [left, right] of the user's walking corridor
     * at a given forward distance. The corridor narrows with distance as perspective projects forward.
     */
    fun getCorridorBounds(distanceMeters: Float, corridorOffset: Float = 0f): Pair<Float, Float> {
        val halfWidth = when {
            distanceMeters <= 1.5f -> 0.28f // ~56% width when close (user shoulder width + safety margin)
            distanceMeters <= 3.0f -> 0.20f // ~40% width at medium distance
            else -> 0.14f                   // ~28% width farther ahead
        }
        val center = (0.50f + corridorOffset).coerceIn(0.30f, 0.70f)
        val left = (center - halfWidth).coerceAtLeast(0.0f)
        val right = (center + halfWidth).coerceAtMost(1.0f)
        return Pair(left, right)
    }

    /**
     * Determines whether an object's bounding box intersects the user's walking corridor.
     * Objects far outside the corridor (e.g. sidewalk benches, parked cars on far curbs)
     * are filtered out to avoid interrupting the user with irrelevant alerts.
     */
    fun isInWalkingCorridor(boundingBox: RectF, distanceMeters: Float, corridorOffset: Float = 0f): Boolean {
        val (corridorLeft, corridorRight) = getCorridorBounds(distanceMeters, corridorOffset)
        val boxLeft = Math.min(boundingBox.left, boundingBox.right)
        val boxRight = Math.max(boundingBox.left, boundingBox.right)

        // Box overlaps corridor if its right edge is past corridor left AND its left edge is before corridor right
        return boxRight > corridorLeft && boxLeft < corridorRight
    }

    /**
     * Determines the optimal evasive direction (LEFT or RIGHT) to steer the user around
     * obstacles by analyzing which side has more clear space.
     */
    fun determineEvasiveDirection(detections: List<DetectedObject>): Position {
        val leftObstacleWeight = detections.filter { it.position == Position.LEFT }
            .sumOf { (5.0f - it.distanceMeters.coerceAtMost(5.0f)).toDouble() }
        val rightObstacleWeight = detections.filter { it.position == Position.RIGHT }
            .sumOf { (5.0f - it.distanceMeters.coerceAtMost(5.0f)).toDouble() }

        return if (leftObstacleWeight <= rightObstacleWeight) Position.LEFT else Position.RIGHT
    }
}
