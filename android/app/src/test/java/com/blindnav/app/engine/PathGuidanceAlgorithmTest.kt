package com.blindnav.app.engine

import android.graphics.RectF
import com.blindnav.app.ml.DetectedObject
import com.blindnav.app.ml.DistanceEstimator
import com.blindnav.app.ml.Position
import com.blindnav.app.ml.ThreatLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PathGuidanceAlgorithmTest {

    private class TestFeedbackEngine : IFeedbackEngine {
        override var currentSpeechRate: Float = 1.15f
        override var currentPriority: SpeechPriority? = null
        override var lastSpokenMessage: String = ""
        override var isVoiceGuidanceMuted: Boolean = false
        override var isVibrationEnabled: Boolean = true

        val spokenMessages = mutableListOf<Pair<String, SpeechPriority>>()
        var dangerVibrations = 0
        var cautionVibrations = 0

        override fun setSpeechRate(rate: Float) { currentSpeechRate = rate }
        override fun setVoiceGuidanceEnabled(enable: Boolean) { isVoiceGuidanceMuted = !enable }
        override fun adjustVolume(increase: Boolean) {}

        override fun speakWithPriority(text: String, priority: SpeechPriority) {
            currentPriority = priority
            lastSpokenMessage = text
            spokenMessages.add(Pair(text, priority))
        }

        override fun speakUrgent(text: String) {
            speakWithPriority(text, SpeechPriority.EMERGENCY)
        }

        override fun speakNormal(text: String) {
            speakWithPriority(text, SpeechPriority.SAFETY_WARNING)
        }

        override fun repeatLastMessage() {}
        override fun vibrateDanger() { dangerVibrations++ }
        override fun vibrateCaution() { cautionVibrations++ }
        override fun shutdown() {}
    }

    private lateinit var feedback: TestFeedbackEngine
    private lateinit var engine: ThreatPrioritizer

    @Before
    fun setUp() {
        feedback = TestFeedbackEngine()
        engine = ThreatPrioritizer(feedback, alertCooldownMs = 4000L, urgentAlertCooldownMs = 1500L)
    }

    private fun createObject(
        label: String,
        distanceMeters: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        threatLevel: ThreatLevel
    ): DetectedObject {
        val rect = RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
        val inCorridor = DistanceEstimator.isInWalkingCorridor(rect, distanceMeters)
        val pos = DistanceEstimator.determinePosition(rect)
        return DetectedObject(
            classId = 0,
            label = label,
            confidence = 0.90f,
            boundingBox = rect,
            distanceMeters = distanceMeters,
            position = pos,
            threatLevel = threatLevel,
            isInCorridor = inCorridor
        )
    }

    @Test
    fun testScenario1_clearPathProducesNoObstacleWarning() {
        val decision = engine.processFrameDetections(emptyList())

        assertEquals(0, feedback.spokenMessages.size)
        assertEquals(0, feedback.dangerVibrations)
        assertEquals(0, feedback.cautionVibrations)
        assertFalse(decision?.isObstaclePresent ?: true)
    }

    @Test
    fun testScenario2_distantObjectOutsideWalkingCorridorIsIgnored() {
        // Bench/chair far to the left (X: 0.05 to 0.18) at 3.5m away
        val farLeftChair = createObject(
            label = "chair",
            distanceMeters = 3.5f,
            left = 0.05f,
            top = 0.40f,
            right = 0.18f,
            bottom = 0.70f,
            threatLevel = ThreatLevel.SAFE
        )

        assertFalse("Object should be outside corridor", farLeftChair.isInCorridor)

        val decision = engine.processFrameDetections(listOf(farLeftChair))

        // System should stay silent for visually impaired user
        assertEquals(0, feedback.spokenMessages.size)
        assertFalse(decision?.isObstaclePresent ?: true)
    }

    @Test
    fun testScenario3_obstacleInCorridorTriggersActionableEvasiveGuidance() {
        // Chair at 1.8m directly in center walking corridor
        val corridorChair = createObject(
            label = "chair",
            distanceMeters = 1.8f,
            left = 0.40f,
            top = 0.30f,
            right = 0.60f,
            bottom = 0.75f,
            threatLevel = ThreatLevel.CAUTION
        )

        assertTrue("Object should be inside corridor", corridorChair.isInCorridor)

        val decision = engine.processFrameDetections(listOf(corridorChair))

        assertEquals(1, feedback.spokenMessages.size)
        val (spokenText, priority) = feedback.spokenMessages[0]

        assertEquals(SpeechPriority.SAFETY_WARNING, priority)
        assertTrue("Guidance must instruct user to move/avoid", spokenText.contains("Obstacle ahead") && (spokenText.contains("Move") || spokenText.contains("right") || spokenText.contains("left")))
        assertEquals(1, feedback.cautionVibrations)
        assertTrue(decision?.isObstaclePresent ?: false)
    }

    @Test
    fun testScenario4_criticalProximityTriggersEmergencyStop() {
        // Person or wall at 0.9m in center corridor
        val closeObstacle = createObject(
            label = "person",
            distanceMeters = 0.9f,
            left = 0.35f,
            top = 0.10f,
            right = 0.65f,
            bottom = 0.90f,
            threatLevel = ThreatLevel.DANGER
        )

        engine.processFrameDetections(listOf(closeObstacle))

        assertEquals(1, feedback.spokenMessages.size)
        val (spokenText, priority) = feedback.spokenMessages[0]

        assertEquals(SpeechPriority.EMERGENCY, priority)
        assertTrue(spokenText.startsWith("STOP"))
        assertEquals(1, feedback.dangerVibrations)
    }

    @Test
    fun testScenario5_multiSensorFusion_vehiclePlusHornTriggersDangerStop() {
        // Car on right side at 2.4m
        val carOnRight = createObject(
            label = "car",
            distanceMeters = 2.4f,
            left = 0.72f,
            top = 0.30f,
            right = 0.95f,
            bottom = 0.80f,
            threatLevel = ThreatLevel.CAUTION
        )

        // Acoustic evidence of vehicle horn detected by EnvironmentalAudioEngine
        val hornEvidence = AudioEvidence(
            type = AudioEventType.VEHICLE_HORN,
            confidence = 0.95f,
            description = "Loud horn alert"
        )

        engine.processFrameDetections(
            detections = listOf(carOnRight),
            audioEvidence = hornEvidence
        )

        assertEquals(1, feedback.spokenMessages.size)
        val (spokenText, priority) = feedback.spokenMessages[0]

        assertEquals(SpeechPriority.EMERGENCY, priority)
        assertTrue(spokenText.contains("Vehicle approaching from your right. Stop."))
        assertEquals(1, feedback.dangerVibrations)
    }

    @Test
    fun testScenario6_cooldownAndThreatEscalation() {
        // Frame 1: Obstacle at 2.8m -> Triggers initial warning
        val obstacleFar = createObject("chair", 2.8f, 0.4f, 0.3f, 0.6f, 0.7f, ThreatLevel.CAUTION)
        engine.processFrameDetections(listOf(obstacleFar))
        assertEquals(1, feedback.spokenMessages.size)

        // Frame 2: Same obstacle at 2.6m -> Suppressed by cooldown
        val obstacleStillFar = createObject("chair", 2.6f, 0.4f, 0.3f, 0.6f, 0.7f, ThreatLevel.CAUTION)
        engine.processFrameDetections(listOf(obstacleStillFar))
        assertEquals(1, feedback.spokenMessages.size) // No repeat announcement!

        // Frame 3: Obstacle rapidly approaches to 1.1m -> Threat Escalation bypasses cooldown!
        val obstacleCritical = createObject("chair", 1.1f, 0.35f, 0.2f, 0.65f, 0.85f, ThreatLevel.DANGER)
        engine.processFrameDetections(listOf(obstacleCritical))

        assertEquals(2, feedback.spokenMessages.size)
        val (escalatedText, escalatedPriority) = feedback.spokenMessages[1]
        assertEquals(SpeechPriority.EMERGENCY, escalatedPriority)
        assertTrue(escalatedText.startsWith("STOP"))
    }
}
