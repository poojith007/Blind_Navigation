package com.blindnav.app.ml

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceEstimatorTest {

    @Test
    fun `object far to the side is outside the walking corridor`() {
        val box = RectF(0.85f, 0.4f, 0.95f, 0.8f) // hugging the far right edge of frame
        val distance = DistanceEstimator.estimateDistance("person", box)

        assertFalse(
            "An object off to the side shouldn't be flagged as in-path",
            DistanceEstimator.isInWalkingCorridor(box, distance)
        )
    }

    @Test
    fun `object centered ahead is inside the walking corridor`() {
        val box = RectF(0.4f, 0.3f, 0.6f, 0.9f) // centered, tall (close)
        val distance = DistanceEstimator.estimateDistance("person", box)

        assertTrue(
            "A centered object ahead should be flagged as in-path",
            DistanceEstimator.isInWalkingCorridor(box, distance)
        )
    }

    @Test
    fun `threat level escalates as distance decreases`() {
        assertEquals(ThreatLevel.SAFE, DistanceEstimator.determineThreatLevel(5.0f, "chair"))
        assertEquals(ThreatLevel.CAUTION, DistanceEstimator.determineThreatLevel(2.0f, "chair"))
        assertEquals(ThreatLevel.DANGER, DistanceEstimator.determineThreatLevel(1.0f, "chair"))
    }

    @Test
    fun `high-risk object classes trigger danger from farther away`() {
        // A car at 2.0m is still DANGER (high-risk class, <=2.2m threshold);
        // a chair at the same 2.0m is only CAUTION.
        assertEquals(ThreatLevel.DANGER, DistanceEstimator.determineThreatLevel(2.0f, "car"))
        assertEquals(ThreatLevel.CAUTION, DistanceEstimator.determineThreatLevel(2.0f, "chair"))
    }

    @Test
    fun `position bucketing matches left center right thirds`() {
        assertEquals(Position.LEFT, DistanceEstimator.determinePosition(0.1f))
        assertEquals(Position.CENTER, DistanceEstimator.determinePosition(0.5f))
        assertEquals(Position.RIGHT, DistanceEstimator.determinePosition(0.9f))
    }
}