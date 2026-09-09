package com.blindnav.app.sensors

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeDetectorTest {

    private class TestableShakeDetector(
        val slopTimeMs: Long = 500L,
        val minCount: Int = 2,
        val cooldownMs: Long = 1200L
    ) {
        var shakeTimestamp = 0L
        var shakeCount = 0

        fun process(x: Float, y: Float, z: Float, now: Long): Boolean {
            val gX = x / 9.80665f
            val gY = y / 9.80665f
            val gZ = z / 9.80665f
            val gForce = kotlin.math.sqrt((gX * gX + gY * gY + gZ * gZ).toDouble()).toFloat()

            if (gForce > 2.1f) {
                if (shakeTimestamp + slopTimeMs > now) {
                    shakeCount++
                    if (shakeCount >= minCount) {
                        shakeTimestamp = now + cooldownMs
                        shakeCount = 0
                        return true
                    }
                } else {
                    shakeCount = 1
                    shakeTimestamp = now
                }
            }
            return false
        }
    }

    @Test
    fun testStationaryDeviceDoesNotTriggerShake() {
        val detector = TestableShakeDetector()
        // Gravity only (1g)
        assertFalse(detector.process(0f, 9.81f, 0f, 1000L))
        assertFalse(detector.process(0f, 9.81f, 0f, 1200L))
        assertFalse(detector.process(0f, 9.81f, 0f, 1400L))
    }

    @Test
    fun testNormalWalkingOscillationsDoNotTriggerShake() {
        val detector = TestableShakeDetector()
        // Walking bounce is around ~1.2g to 1.5g (~12-14 m/s^2)
        assertFalse(detector.process(2.0f, 12.0f, 1.0f, 1000L))
        assertFalse(detector.process(1.5f, 14.0f, 2.0f, 1300L))
        assertFalse(detector.process(0.5f, 13.0f, 1.0f, 1600L))
    }

    @Test
    fun testIntentionalDoubleShakeTriggersActivation() {
        val detector = TestableShakeDetector()
        val t0 = 1000L

        // First shake: ~2.5g
        val firstTrigger = detector.process(18f, 15f, 5f, t0)
        assertFalse(firstTrigger) // Needs 2 shakes within slop time

        // Second shake: 200ms later within 500ms window
        val secondTrigger = detector.process(-19f, 14f, 4f, t0 + 200L)
        assertTrue("Two intentional shakes within slop window must trigger voice assistant", secondTrigger)
    }

    @Test
    fun testIsolatedSingleShakesDoNotTrigger() {
        val detector = TestableShakeDetector()
        val t0 = 1000L

        // First shake
        assertFalse(detector.process(20f, 10f, 5f, t0))

        // Second shake happens 900ms later (after 500ms slop expired)
        assertFalse("Expired shake should reset count and not trigger", detector.process(20f, 10f, 5f, t0 + 900L))
    }
}
