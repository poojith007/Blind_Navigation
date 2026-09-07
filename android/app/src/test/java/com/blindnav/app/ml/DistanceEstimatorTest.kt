package com.blindnav.app.ml

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceEstimatorTest {

    private fun createRect(l: Float, t: Float, r: Float, b: Float): RectF {
        return RectF().apply {
            left = l
            top = t
            right = r
            bottom = b
        }
    }

    @Test
    fun testDistanceEstimation_personStandard() {
        // Person real height = 1.70m, focal constant = 0.85
        // Box height fraction = 0.85
        // Expected distance = (0.85 * 1.70) / 0.85 = 1.7m
        val box = createRect(0.2f, 0.1f, 0.6f, 0.95f) // height = 0.85f
        val distance = DistanceEstimator.estimateDistance("person", box)
        assertEquals(1.7f, distance, 0.1f)

        val directDistance = DistanceEstimator.estimateDistance("person", 0.85f)
        assertEquals(1.7f, directDistance, 0.1f)
    }

    @Test
    fun testDistanceEstimation_carStandard() {
        // Car real height = 1.50m, focal constant = 0.85
        // Box height fraction = 0.50
        // Expected distance = (0.85 * 1.50) / 0.50 = 2.55 -> ~2.6m
        val box = createRect(0.1f, 0.2f, 0.8f, 0.7f) // height = 0.50f
        val distance = DistanceEstimator.estimateDistance("car", box)
        assertEquals(2.6f, distance, 0.1f)
    }

    @Test
    fun testDistanceEstimation_zeroHeightGraceful() {
        val box = createRect(0.2f, 0.5f, 0.4f, 0.5f) // height = 0.0f
        val distance = DistanceEstimator.estimateDistance("person", box)
        assertEquals(99.0f, distance, 0.1f)
    }

    @Test
    fun testDeterminePosition_leftCenterRight() {
        val leftBox = createRect(0.05f, 0.2f, 0.25f, 0.8f) // centerX = 0.15
        assertEquals(Position.LEFT, DistanceEstimator.determinePosition(leftBox))

        val centerBox = createRect(0.40f, 0.2f, 0.60f, 0.8f) // centerX = 0.50
        assertEquals(Position.CENTER, DistanceEstimator.determinePosition(centerBox))

        val rightBox = createRect(0.70f, 0.2f, 0.90f, 0.8f) // centerX = 0.80
        assertEquals(Position.RIGHT, DistanceEstimator.determinePosition(rightBox))
    }

    @Test
    fun testDetermineThreatLevel_dangerCautionSafe() {
        // Normal object very close -> DANGER
        assertEquals(ThreatLevel.DANGER, DistanceEstimator.determineThreatLevel(0.8f, "chair"))

        // High risk vehicle moderately close (2.0m) -> DANGER
        assertEquals(ThreatLevel.DANGER, DistanceEstimator.determineThreatLevel(2.0f, "car"))
        assertEquals(ThreatLevel.DANGER, DistanceEstimator.determineThreatLevel(1.9f, "stairs"))

        // Normal object moderately close (1.8m) -> CAUTION
        assertEquals(ThreatLevel.CAUTION, DistanceEstimator.determineThreatLevel(1.8f, "chair"))

        // Any object far away (> 2.5m) -> SAFE
        assertEquals(ThreatLevel.SAFE, DistanceEstimator.determineThreatLevel(3.2f, "person"))
        assertEquals(ThreatLevel.SAFE, DistanceEstimator.determineThreatLevel(4.0f, "truck"))
    }
}
