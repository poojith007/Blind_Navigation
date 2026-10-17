package com.blindnav.app.engine

import android.graphics.RectF
import com.blindnav.app.ml.DetectedObject
import com.blindnav.app.ml.Position
import com.blindnav.app.ml.ThreatLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the alert-filtering scenarios called out in the upgrade spec:
 *  1. A single transient detection should not immediately alert.
 *  2. A persistent relevant obstacle should eventually trigger an alert.
 *  3. The same obstacle should not generate repeated announcements every frame.
 *  4. A newly appearing (different) hazard should still be announced.
 *  5. A worsening/imminent hazard can escalate despite an active ordinary cooldown.
 *  6. An irrelevant object outside the walking path is not treated as an immediate threat.
 *  7. Safe Failure Mandate: Never show "PATH CLEAR" if perception is degraded.
 */
class ThreatPrioritizerTest {

    private fun makeObject(
        label: String,
        distanceMeters: Float,
        threatLevel: ThreatLevel,
        position: Position = Position.CENTER,
        confidence: Float = 0.80f,
        isInCorridor: Boolean = true
    ) = DetectedObject(
        classId = 0,
        label = label,
        confidence = confidence,
        boundingBox = RectF(0.4f, 0.3f, 0.6f, 0.9f),
        distanceMeters = distanceMeters,
        position = position,
        threatLevel = threatLevel,
        isInCorridor = isInCorridor
    )

    @Test
    fun `single transient detection does not immediately alert`() {
        val fake = FakeFeedbackEngine()
        val prioritizer = ThreatPrioritizer(fake)
        val chair = makeObject("chair", distanceMeters = 2.0f, threatLevel = ThreatLevel.CAUTION)

        prioritizer.processFrameDetections(listOf(chair))

        assertTrue("A single frame should not be enough to trigger speech", fake.spoken.isEmpty())
    }

    @Test
    fun `persistent relevant obstacle eventually triggers an alert`() {
        val fake = FakeFeedbackEngine()
        val prioritizer = ThreatPrioritizer(fake)
        val chair = makeObject("chair", distanceMeters = 2.0f, threatLevel = ThreatLevel.CAUTION)

        prioritizer.processFrameDetections(listOf(chair)) // frame 1: not yet confirmed
        prioritizer.processFrameDetections(listOf(chair)) // frame 2: now confirmed

        assertEquals(1, fake.spoken.size)
        assertTrue(fake.spoken[0].text.isNotBlank())
    }

    @Test
    fun `confirmed obstacle does not announce again on every subsequent frame`() {
        val fake = FakeFeedbackEngine()
        val prioritizer = ThreatPrioritizer(fake)
        val chair = makeObject("chair", distanceMeters = 2.0f, threatLevel = ThreatLevel.CAUTION)

        repeat(6) { prioritizer.processFrameDetections(listOf(chair)) }

        assertEquals(
            "An unchanging obstacle should only announce once, not on every frame",
            1, fake.spoken.size
        )
    }

    @Test
    fun `a different newly appearing hazard is still announced`() {
        val fake = FakeFeedbackEngine()
        val prioritizer = ThreatPrioritizer(fake, globalMinGapMs = 50L, globalMinGapUrgentMs = 50L)
        val chair = makeObject("chair", distanceMeters = 2.0f, threatLevel = ThreatLevel.CAUTION)
        prioritizer.processFrameDetections(listOf(chair))
        prioritizer.processFrameDetections(listOf(chair))
        assertEquals(1, fake.spoken.size)

        Thread.sleep(60) // clear the (shortened, for this test) global pacing gap
        val pole = makeObject("pole", distanceMeters = 1.8f, threatLevel = ThreatLevel.CAUTION)
        prioritizer.processFrameDetections(listOf(pole))
        prioritizer.processFrameDetections(listOf(pole))

        assertEquals("A distinct new hazard should still get its own announcement", 2, fake.spoken.size)
    }

    @Test
    fun `a worsening hazard escalates immediately despite an active ordinary cooldown`() {
        val fake = FakeFeedbackEngine()
        val prioritizer = ThreatPrioritizer(fake)
        val cautionCar = makeObject("car", distanceMeters = 3.0f, threatLevel = ThreatLevel.CAUTION, confidence = 0.8f)

        prioritizer.processFrameDetections(listOf(cautionCar))
        prioritizer.processFrameDetections(listOf(cautionCar))
        assertEquals(1, fake.spoken.size)
        assertEquals(SpeechPriority.SAFETY_WARNING, fake.spoken[0].priority)

        // Same object, now an imminent DANGER — must not be blocked by cooldown
        val dangerCar = makeObject("car", distanceMeters = 1.0f, threatLevel = ThreatLevel.DANGER, confidence = 0.8f)
        prioritizer.processFrameDetections(listOf(dangerCar))

        assertEquals(2, fake.spoken.size)
        assertEquals(SpeechPriority.EMERGENCY, fake.spoken[1].priority)
        assertTrue("A DANGER escalation should trigger a vibration too", fake.dangerVibrations >= 1)
    }

    @Test
    fun `an irrelevant object outside the walking path is not treated as a threat`() {
        val fake = FakeFeedbackEngine()
        val prioritizer = ThreatPrioritizer(fake)
        val benchOffPath = makeObject(
            "bench", distanceMeters = 4.0f, threatLevel = ThreatLevel.SAFE, isInCorridor = false
        )

        repeat(4) { prioritizer.processFrameDetections(listOf(benchOffPath)) }

        assertTrue(
            "An object outside the walking corridor at a safe distance should never speak",
            fake.spoken.isEmpty()
        )
    }

    @Test
    fun `a high-confidence imminent danger can alert on the very first frame`() {
        val fake = FakeFeedbackEngine()
        val prioritizer = ThreatPrioritizer(fake)
        val imminent = makeObject("person", distanceMeters = 0.8f, threatLevel = ThreatLevel.DANGER, confidence = 0.9f)

        prioritizer.processFrameDetections(listOf(imminent))

        assertEquals(1, fake.spoken.size)
        assertEquals(SpeechPriority.EMERGENCY, fake.spoken[0].priority)
    }

    @Test
    fun `a low-confidence first-time danger still waits for confirmation`() {
        val fake = FakeFeedbackEngine()
        val prioritizer = ThreatPrioritizer(fake)
        val uncertain = makeObject("person", distanceMeters = 0.8f, threatLevel = ThreatLevel.DANGER, confidence = 0.3f)

        prioritizer.processFrameDetections(listOf(uncertain))

        assertTrue(
            "A low-confidence detection should not bypass temporal confirmation",
            fake.spoken.isEmpty()
        )
    }

    @Test
    fun `safe failure when perception degraded`() {
        val fake = FakeFeedbackEngine()
        val prioritizer = ThreatPrioritizer(fake)
        val decision = prioritizer.processFrameDetections(
            detections = emptyList(),
            audioEvidence = null,
            navProgress = null,
            isUserMoving = true,
            isPerceptionDegraded = true
        )

        assertNotNull(decision)
        assertTrue(decision!!.isPathUnclear)
        assertEquals("Path unclear. Please stop.", decision.instruction)
        assertTrue(decision.visualHeadline.contains("PATH UNCLEAR"))
        assertFalse(decision.visualHeadline.contains("PATH CLEAR"))
    }
}
