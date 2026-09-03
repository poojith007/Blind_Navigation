package com.blindnav.app.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class FallDetectorAlgorithmTest {

    private fun calculateTotalAcceleration(x: Float, y: Float, z: Float): Float {
        return sqrt((x * x + y * y + z * z).toDouble()).toFloat()
    }

    private fun isFreeFall(accel: Float): Boolean = accel < 4.5f

    private fun isImpactSpike(accel: Float): Boolean = accel > 26.0f

    @Test
    fun testRestingGravityVector() {
        // Device resting upright on table: [0, 9.8, 0]
        val accel = calculateTotalAcceleration(0f, 9.81f, 0f)
        assertEquals(9.81f, accel, 0.05f)
        assertFalse(isFreeFall(accel))
        assertFalse(isImpactSpike(accel))
    }

    @Test
    fun testFreeFallPhase() {
        // In free-fall, sensor values drop near zero
        val accel = calculateTotalAcceleration(0.8f, 1.2f, 0.5f)
        assertTrue("Acceleration should indicate free-fall", isFreeFall(accel))
        assertFalse(isImpactSpike(accel))
    }

    @Test
    fun testImpactSpikePhase() {
        // Sudden violent deceleration / ground impact
        val accel = calculateTotalAcceleration(18.0f, 18.0f, 12.0f)
        assertEquals(28.14f, accel, 0.1f)
        assertTrue("Acceleration should trigger impact spike", isImpactSpike(accel))
        assertFalse(isFreeFall(accel))
    }

    @Test
    fun testNormalWalkingMotionDoesNotTriggerFall() {
        // Typical walking / jogging oscillations (typically 8 to 15 m/s^2)
        val walkStep1 = calculateTotalAcceleration(2.1f, 11.2f, 3.4f)
        assertFalse(isFreeFall(walkStep1))
        assertFalse(isImpactSpike(walkStep1))

        val walkStep2 = calculateTotalAcceleration(1.5f, 8.4f, 2.0f)
        assertFalse(isFreeFall(walkStep2))
        assertFalse(isImpactSpike(walkStep2))
    }
}
