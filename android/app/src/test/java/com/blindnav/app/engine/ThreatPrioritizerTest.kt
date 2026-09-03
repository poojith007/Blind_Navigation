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
        val urgentSpoken = mutableListOf<String>()
        val normalSpoken = mutableListOf<String>()
        var dangerVibrations = 0
        var cautionVibrations = 0

        override fun setSpeechRate(rate: Float) { currentSpeechRate = rate }
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
        assertTrue(fakeFeedback.urgentSpoken[0].contains("Car"))
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
}
