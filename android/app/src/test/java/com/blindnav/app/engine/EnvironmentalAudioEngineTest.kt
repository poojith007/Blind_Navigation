package com.blindnav.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class EnvironmentalAudioEngineTest {

    private fun generateSineWaveBuffer(frequencyHz: Double, sampleRate: Int = 16000, length: Int = 2048, amplitude: Double = 15000.0): ShortArray {
        val buffer = ShortArray(length)
        for (i in 0 until length) {
            val t = i.toDouble() / sampleRate
            val sample = sin(2.0 * Math.PI * frequencyHz * t) * amplitude
            buffer[i] = sample.toInt().coerceIn(-32768, 32767).toShort()
        }
        return buffer
    }

    @Test
    fun testClassifyAudioBuffer_hornDetection() {
        // Vehicle horn typically around 400 - 450 Hz with loud volume
        val buffer = generateSineWaveBuffer(frequencyHz = 420.0, amplitude = 18000.0)
        
        val engine = EnvironmentalAudioEngine()

        val evidence = engine.classifyAudioBuffer(buffer, buffer.size)

        assertNotNull("Should detect vehicle horn", evidence)
        assertEquals(AudioEventType.VEHICLE_HORN, evidence?.type)
        assertTrue(evidence?.confidence ?: 0f >= 0.80f)
    }

    @Test
    fun testClassifyAudioBuffer_bicycleBellDetection() {
        // Bicycle bell is high-pitched around 2500 Hz
        val buffer = generateSineWaveBuffer(frequencyHz = 2600.0, amplitude = 10000.0)
        val engine = EnvironmentalAudioEngine()

        val evidence = engine.classifyAudioBuffer(buffer, buffer.size)

        assertNotNull("Should detect bicycle bell", evidence)
        assertEquals(AudioEventType.BICYCLE_BELL, evidence?.type)
    }

    @Test
    fun testClassifyAudioBuffer_engineRumbleDetection() {
        // Engine rumble is low-frequency around 120 Hz
        val buffer = generateSineWaveBuffer(frequencyHz = 120.0, amplitude = 12000.0)
        val engine = EnvironmentalAudioEngine()

        val evidence = engine.classifyAudioBuffer(buffer, buffer.size)

        assertNotNull("Should detect engine rumble", evidence)
        assertEquals(AudioEventType.ENGINE_RUMBLE, evidence?.type)
    }

    @Test
    fun testClassifyAudioBuffer_silenceIgnoredByNoiseGate() {
        // Ambient room noise below RMS threshold
        val buffer = generateSineWaveBuffer(frequencyHz = 400.0, amplitude = 200.0)
        val engine = EnvironmentalAudioEngine()

        val evidence = engine.classifyAudioBuffer(buffer, buffer.size)

        assertNull("Low amplitude sounds should be filtered out", evidence)
    }

    @Test
    fun testSimulateAudioEvent_updatesRecentEvidence() {
        var callbackReceived: AudioEvidence? = null
        val engine = EnvironmentalAudioEngine(
            onAudioEvidenceDetected = { callbackReceived = it }
        )

        engine.simulateAudioEvent(AudioEventType.SIREN, 0.95f)

        assertNotNull(callbackReceived)
        assertEquals(AudioEventType.SIREN, callbackReceived?.type)

        val recent = engine.getRecentAudioEvidence()
        assertNotNull(recent)
        assertEquals(AudioEventType.SIREN, recent?.type)
        assertTrue(recent?.isRecent() ?: false)
    }
}
