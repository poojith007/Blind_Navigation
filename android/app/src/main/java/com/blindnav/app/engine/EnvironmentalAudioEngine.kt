package com.blindnav.app.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

enum class AudioEventType {
    NONE,
    VEHICLE_HORN,
    SIREN,
    BICYCLE_BELL,
    ENGINE_RUMBLE
}

data class AudioEvidence(
    val type: AudioEventType,
    val confidence: Float,
    val timestampMs: Long = System.currentTimeMillis(),
    val description: String = ""
) {
    fun isRecent(windowMs: Long = 3500L): Boolean {
        return (System.currentTimeMillis() - timestampMs) <= windowMs
    }
}

/**
 * Environmental Audio Classifier that listens to background acoustic cues (horns, sirens,
 * bells, vehicle engines) and provides contextual evidence for safety decision making.
 * Does NOT generate spoken audio directly to prevent voice spam.
 */
class EnvironmentalAudioEngine(
    private val context: Context? = null,
    private val onAudioEvidenceDetected: ((AudioEvidence) -> Unit)? = null
) {
    private val tag = "EnvironmentalAudio"

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val isPausedForVoiceCommand = AtomicBoolean(false)

    @Volatile
    private var latestEvidence: AudioEvidence? = null

    // Siren modulation tracking
    private var sirenPeakHistory = mutableListOf<Float>()

    fun start() {
        if (isRecording.get()) return
        if (context == null) {
            Log.w(tag, "Context is null. Running in simulated acoustic mode.")
            return
        }

        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "RECORD_AUDIO permission not granted. Running in simulated acoustic mode.")
            return
        }

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = Math.max(minBufferSize, 2048)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(tag, "AudioRecord failed to initialize. Background audio classifier inactive.")
                return
            }

            audioRecord?.startRecording()
            isRecording.set(true)

            recordingThread = Thread({ processAudioStream(bufferSize) }, "EnvironmentalAudioThread").apply {
                isDaemon = true
                start()
            }
            Log.i(tag, "Environmental Audio Engine started successfully.")
        } catch (e: SecurityException) {
            Log.w(tag, "SecurityException starting audio recording: ${e.message}")
        } catch (e: Exception) {
            Log.e(tag, "Error initializing EnvironmentalAudioEngine: ${e.message}")
        }
    }

    private fun processAudioStream(bufferSize: Int) {
        val audioBuffer = ShortArray(bufferSize)

        while (isRecording.get()) {
            if (isPausedForVoiceCommand.get()) {
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                continue
            }

            val readBytes = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: -1
            if (readBytes > 0) {
                val evidence = classifyAudioBuffer(audioBuffer, readBytes)
                if (evidence != null && evidence.type != AudioEventType.NONE) {
                    latestEvidence = evidence
                    onAudioEvidenceDetected?.invoke(evidence)
                }
            }
        }
    }

    /**
     * Performs lightweight feature extraction (RMS energy, Zero Crossing Rate,
     * spectral energy in indicative bands) to identify dangerous environmental sounds.
     */
    fun classifyAudioBuffer(buffer: ShortArray, length: Int): AudioEvidence? {
        if (length <= 0) return null

        // 1. Calculate RMS Energy (Loudness)
        var sumSquares = 0.0
        var zeroCrossings = 0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sumSquares += sample * sample
            if (i > 0 && ((buffer[i] >= 0 && buffer[i - 1] < 0) || (buffer[i] < 0 && buffer[i - 1] >= 0))) {
                zeroCrossings++
            }
        }
        val rms = sqrt(sumSquares / length)

        // Noise gate: ignore low-amplitude background noise
        if (rms < 600.0) {
            return null
        }

        // Approximate dominant frequency from Zero Crossing Rate (ZCR)
        // ZCR * (sampleRate / 2) / length
        val approximateFrequency = (zeroCrossings.toFloat() * sampleRate) / (2.0f * length)

        // 2. High-frequency Transient Peak -> Bicycle Bell (~2000 Hz to 3500 Hz)
        if (approximateFrequency in 1900f..3600f && rms > 1200.0) {
            return AudioEvidence(
                type = AudioEventType.BICYCLE_BELL,
                confidence = 0.85f,
                description = "Bicycle bell alert detected"
            )
        }

        // 3. Sustained Loud Dual / Mid Horn Peak -> Vehicle Horn (~350 Hz to 540 Hz or ~2500 Hz to 3200 Hz)
        if ((approximateFrequency in 340f..540f || approximateFrequency in 2500f..3200f) && rms > 2200.0) {
            return AudioEvidence(
                type = AudioEventType.VEHICLE_HORN,
                confidence = 0.90f,
                description = "Vehicle horn detected"
            )
        }

        // 4. Low-Frequency Rumble -> Engine / Heavy Vehicle (~80 Hz to 240 Hz)
        if (approximateFrequency in 75f..240f && rms > 1500.0) {
            return AudioEvidence(
                type = AudioEventType.ENGINE_RUMBLE,
                confidence = 0.80f,
                description = "Vehicle engine sound detected"
            )
        }

        // 5. Undulating frequency tracking -> Emergency Siren (~650 Hz to 1350 Hz)
        if (approximateFrequency in 650f..1400f && rms > 1400.0) {
            sirenPeakHistory.add(approximateFrequency)
            if (sirenPeakHistory.size > 8) sirenPeakHistory.removeAt(0)

            if (sirenPeakHistory.size >= 5) {
                val maxF = sirenPeakHistory.maxOrNull() ?: 0f
                val minF = sirenPeakHistory.minOrNull() ?: 0f
                if ((maxF - minF) in 200f..700f) {
                    return AudioEvidence(
                        type = AudioEventType.SIREN,
                        confidence = 0.88f,
                        description = "Emergency siren detected"
                    )
                }
            }
        }

        return null
    }

    /**
     * Retrieves the most recent significant acoustic evidence within the validity window (default 3.5s).
     */
    fun getRecentAudioEvidence(windowMs: Long = 3500L): AudioEvidence? {
        val evidence = latestEvidence
        return if (evidence != null && evidence.isRecent(windowMs)) evidence else null
    }

    /**
     * Programmatic simulation hook for unit tests, examiner demonstrations, and automated testing.
     */
    fun simulateAudioEvent(type: AudioEventType, confidence: Float = 0.90f) {
        val evidence = AudioEvidence(
            type = type,
            confidence = confidence,
            timestampMs = System.currentTimeMillis(),
            description = "Simulated ${type.name}"
        )
        latestEvidence = evidence
        onAudioEvidenceDetected?.invoke(evidence)
    }

    fun pauseListening() {
        isPausedForVoiceCommand.set(true)
    }

    fun resumeListening() {
        isPausedForVoiceCommand.set(false)
    }

    fun stop() {
        isRecording.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null
        recordingThread = null
    }
}
