package com.blindnav.app.engine

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class FeedbackEngine(private val context: Context) : IFeedbackEngine, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isTtsReady = false

    private var isSpeakingActive = false
    private var lastSpokenTimestamp = 0L

    override var currentSpeechRate: Float = 1.15f
        private set

    override var lastSpokenMessage: String = ""
        private set

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
                tts?.setSpeechRate(currentSpeechRate)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeakingActive = true
                    }

                    override fun onDone(utteranceId: String?) {
                        isSpeakingActive = false
                    }

                    override fun onError(utteranceId: String?) {
                        isSpeakingActive = false
                    }
                })
            }
        }
    }

    override fun setSpeechRate(rate: Float) {
        currentSpeechRate = rate.coerceIn(0.5f, 2.5f)
        if (isTtsReady) {
            tts?.setSpeechRate(currentSpeechRate)
        }
    }

    override fun speakUrgent(text: String) {
        val now = System.currentTimeMillis()
        // Prevent stuttering: do not cut off active utterance if it's the same warning or started very recently
        if (isSpeakingActive && text == lastSpokenMessage && (now - lastSpokenTimestamp) < 1800L) {
            return
        }
        if (isSpeakingActive && (now - lastSpokenTimestamp) < 1200L) {
            return
        }

        lastSpokenMessage = text
        lastSpokenTimestamp = now
        if (!isTtsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "URGENT_${now}")
    }

    override fun speakNormal(text: String) {
        val now = System.currentTimeMillis()
        if (isSpeakingActive && text == lastSpokenMessage && (now - lastSpokenTimestamp) < 2500L) {
            return
        }

        lastSpokenMessage = text
        lastSpokenTimestamp = now
        if (!isTtsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "NORMAL_${now}")
    }

    override fun repeatLastMessage() {
        if (lastSpokenMessage.isNotBlank()) {
            speakNormal("Repeating: $lastSpokenMessage")
        } else {
            speakNormal("No previous announcements to repeat.")
        }
    }

    override fun vibrateDanger() {
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Heavy continuous double pulse pattern
            val timings = longArrayOf(0, 200, 100, 400)
            val amplitudes = intArrayOf(0, 255, 0, 255)
            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }
    }

    override fun vibrateCaution() {
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        vibrator.cancel()
    }
}
