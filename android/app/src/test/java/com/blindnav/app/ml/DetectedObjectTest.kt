package com.blindnav.app.ml

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectedObjectTest {

    @Test
    fun testToTtsPrompt_dangerPrefixAndDirection() {
        val dangerCar = DetectedObject(
            classId = 2,
            label = "car",
            confidence = 0.95f,
            boundingBox = RectF(0.1f, 0.2f, 0.4f, 0.7f),
            distanceMeters = 1.8f,
            position = Position.LEFT,
            threatLevel = ThreatLevel.DANGER
        )

        val prompt = dangerCar.toTtsPrompt()
        assertTrue(prompt.startsWith("Warning!"))
        assertTrue(prompt.contains("Car"))
        assertTrue(prompt.contains("on your left"))
        assertTrue(prompt.contains("1.8 meters"))
    }

    @Test
    fun testToTtsPrompt_normalAhead() {
        val chair = DetectedObject(
            classId = 56,
            label = "chair",
            confidence = 0.88f,
            boundingBox = RectF(0.4f, 0.3f, 0.6f, 0.8f),
            distanceMeters = 2.1f,
            position = Position.CENTER,
            threatLevel = ThreatLevel.CAUTION
        )

        val prompt = chair.toTtsPrompt()
        assertEquals("Chair ahead, 2.1 meters.", prompt)
    }

    @Test
    fun testToTtsPrompt_safeRight() {
        val person = DetectedObject(
            classId = 0,
            label = "person",
            confidence = 0.90f,
            boundingBox = RectF(0.7f, 0.1f, 0.9f, 0.9f),
            distanceMeters = 3.5f,
            position = Position.RIGHT,
            threatLevel = ThreatLevel.SAFE
        )

        val prompt = person.toTtsPrompt()
        assertEquals("Person on your right, 3.5 meters.", prompt)
    }
}
