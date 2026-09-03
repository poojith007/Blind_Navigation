package com.blindnav.app.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.blindnav.app.ml.Position
import com.blindnav.app.ml.ThreatLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.sin

class SpatialSoundEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private val sampleRate = 44100
    private var isPlaying = false

    /**
     * Plays an instantaneous directional audio cue.
     * @param position Spatial placement (LEFT, CENTER, RIGHT)
     * @param threatLevel Threat level to determine frequency and urgency
     * @param distanceMeters Distance in meters to scale beep pulse duration
     */
    fun playSpatialCue(position: Position, threatLevel: ThreatLevel, distanceMeters: Float) {
        scope.launch {
            try {
                val (frequency, durationMs) = when (threatLevel) {
                    ThreatLevel.DANGER -> Pair(1200.0, 150)
                    ThreatLevel.CAUTION -> Pair(800.0, 120)
                    ThreatLevel.SAFE -> Pair(480.0, 80)
                }

                val (leftVol, rightVol) = when (position) {
                    Position.LEFT -> Pair(1.0f, 0.15f)
                    Position.RIGHT -> Pair(0.15f, 1.0f)
                    Position.CENTER -> Pair(0.85f, 0.85f)
                }

                generateAndPlayTone(frequency, durationMs, leftVol, rightVol)

                // Double beep for critical danger
                if (threatLevel == ThreatLevel.DANGER) {
                    kotlinx.coroutines.delay(60)
                    generateAndPlayTone(frequency * 1.1, durationMs, leftVol, rightVol)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun generateAndPlayTone(
        frequency: Double,
        durationMs: Int,
        leftVolume: Float,
        rightVolume: Float
    ) {
        val numSamples = (durationMs * sampleRate) / 1000
        val sample = DoubleArray(numSamples)
        val generatedSnd = ShortArray(numSamples * 2) // Stereo: [Left0, Right0, Left1, Right1, ...]

        // Generate Sine Wave with smooth fade-out envelope to avoid audio clicks
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val envelope = (1.0 - (i.toDouble() / numSamples)).coerceIn(0.0, 1.0)
            sample[i] = sin(2.0 * Math.PI * frequency * t) * envelope
        }

        var idx = 0
        for (i in 0 until numSamples) {
            val valShort = (sample[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()
            val leftSample = (valShort * leftVolume).toInt().toShort()
            val rightSample = (valShort * rightVolume).toInt().toShort()

            generatedSnd[idx++] = leftSample
            generatedSnd[idx++] = rightSample
        }

        val bufferSize = generatedSnd.size * 2
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(generatedSnd, 0, generatedSnd.size)
        audioTrack.play()

        // Clean up track after tone finishes
        scope.launch {
            kotlinx.coroutines.delay(durationMs + 100L)
            try {
                audioTrack.stop()
                audioTrack.release()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }
}
