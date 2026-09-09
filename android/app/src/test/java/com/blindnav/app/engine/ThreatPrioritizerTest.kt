package com.blindnav.app.engine

import android.graphics.RectF
import com.blindnav.app.ml.DetectedObject
import com.blindnav.app.ml.Position
import com.blindnav.app.ml.ThreatLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThreatPrioritizerTest {

    private class FakeFeedbackEngine : IFeedbackEngine {
        override var currentSpeechRate: Float = 1.1f
        override var lastSpokenMessage: String = ""
        override var currentPriority: SpeechPriority? = null
        override var isVoiceGuidanceMuted: Boolean = false
        override var isVibrationEnabled: Boolean = true
        val urgentSpoken = mutableListOf<String>()
        val normalSpoken = mutableListOf<String>()
        var dangerVibrations = 0
        var cautionVibrations = 0

        override fun setSpeechRate(rate: Float) { currentSpeechRate = rate }
        override fun setVoiceGuidanceEnabled(enable: Boolean) { isVoiceGuidanceMuted = !enable }
        override fun adjustVolume(increase: Boolean) {}
        override fun speakWithPriority(text: String, priority: SpeechPriority) {
            currentPriority = priority
            lastSpokenMessage = text
            when (priority) {
                SpeechPriority.EMERGENCY -> speakUrgent(text)
                SpeechPriority.SAFETY_WARNING -> speakNormal(text)
                else -> normalSpoken.add(text)
            }
        }
        override fun speakUrgent(text: String) { urgentSpoken.add(text); lastSpokenMessage = text }
        override fun speakNormal(text: String) { normalSpoken.add(text); lastSpokenMessage = text }
        override fun repeatLastMessage() {}
        override fun vibrateDanger() { dangerVibrations++ }
        override fun vibrateCaution() { cautionVibrations++ }
        override fun shutdown() {}
    }

    private lateinit var fakeFeedback: FakeFeedbackEngine
    private lateinit var prioritizer: ThreatPrioritizer

    @Before
    fun setup() {
        fakeFeedback = FakeFeedbackEngine()
        prioritizer = ThreatPrioritizer(fakeFeedback, alertCooldownMs = 3000L)
    }

    @Test
    fun testProcessFrameDetections_prioritizesDangerOverSafe() {
        val safePerson = DetectedObject(
            classId = 0,
            label = "person",
            confidence = 0.90f,
            boundingBox = RectF(0.4f, 0.1f, 0.6f, 0.5f),
            distanceMeters = 3.5f,
            position = Position.CENTER,
            threatLevel = ThreatLevel.SAFE
        )

        val dangerCar = DetectedObject(
            classId = 2,
            label = "car",
            confidence = 0.95f,
            boundingBox = RectF(0.1f, 0.2f, 0.5f, 0.8f),
            distanceMeters = 1.2f,
            position = Position.LEFT,
            threatLevel = ThreatLevel.DANGER
        )

        prioritizer.processFrameDetections(listOf(safePerson, dangerCar))

        assertEquals(1, fakeFeedback.urgentSpoken.size)
        assertTrue(fakeFeedback.urgentSpoken[0].contains("Vehicle") || fakeFeedback.urgentSpoken[0].contains("Stop") || fakeFeedback.urgentSpoken[0].contains("STOP"))
        assertEquals(1, fakeFeedback.dangerVibrations)
        assertEquals(0, fakeFeedback.normalSpoken.size)
    }

    @Test
    fun testProcessFrameDetections_cooldownSuppressesDuplicateCaution() {
        val cautionChair = DetectedObject(
            classId = 56,
            label = "chair",
            confidence = 0.85f,
            boundingBox = RectF(0.4f, 0.3f, 0.6f, 0.7f),
            distanceMeters = 2.0f,
            position = Position.CENTER,
            threatLevel = ThreatLevel.CAUTION
        )

        // First frame triggers
        prioritizer.processFrameDetections(listOf(cautionChair))
        assertEquals(1, fakeFeedback.normalSpoken.size)
        assertEquals(1, fakeFeedback.cautionVibrations)

        // Immediate subsequent frame with same object should be suppressed by cooldown
        prioritizer.processFrameDetections(listOf(cautionChair))
        assertEquals(1, fakeFeedback.normalSpoken.size)
        assertEquals(1, fakeFeedback.cautionVibrations)
    }

    @Test
    fun testProcessFrameDetections_safeFailureWhenPerceptionDegraded() {
        // Safe Failure Mandate: Never show "PATH CLEAR" if perception is degraded/uncertain
        val decision = prioritizer.processFrameDetections(
            detections = emptyList(),
            audioEvidence = null,
            navProgress = null,
            isUserMoving = true,
            isPerceptionDegraded = true
        )

        org.junit.Assert.assertNotNull(decision)
        assertTrue(decision!!.isPathUnclear)
        assertEquals("Path unclear. Please stop.", decision.instruction)
        assertTrue(decision.visualHeadline.contains("PATH UNCLEAR"))
        org.junit.Assert.assertFalse(decision.visualHeadline.contains("PATH CLEAR"))
    }
}
