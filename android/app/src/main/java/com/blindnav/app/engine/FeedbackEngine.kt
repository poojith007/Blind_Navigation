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
                        currentPriority = null
                    }

                    override fun onError(utteranceId: String?) {
                        isSpeakingActive = false
                        currentPriority = null
                    }
                })
            }
        }
    }

    override var currentPriority: SpeechPriority? = null
        private set

    override fun setSpeechRate(rate: Float) {
        currentSpeechRate = rate.coerceIn(0.5f, 2.5f)
        if (isTtsReady) {
            tts?.setSpeechRate(currentSpeechRate)
        }
    }

    override fun speakWithPriority(text: String, priority: SpeechPriority) {
        val now = System.currentTimeMillis()

        // 1. Priority 1: EMERGENCY (Immediate Collision / Stop)
        if (priority == SpeechPriority.EMERGENCY) {
            // Deduplicate identical emergency only if uttered within 1.2s
            if (isSpeakingActive && text == lastSpokenMessage && (now - lastSpokenTimestamp) < 1200L) {
                return
            }
            lastSpokenMessage = text
            lastSpokenTimestamp = now
            currentPriority = SpeechPriority.EMERGENCY
            if (!isTtsReady) return
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "EMERGENCY_${now}")
            return
        }

        // 2. Priority 2: SAFETY WARNING (Obstacle / Evasive action)
        if (priority == SpeechPriority.SAFETY_WARNING) {
            // Cannot interrupt an active Emergency announcement
            if (isSpeakingActive && currentPriority == SpeechPriority.EMERGENCY && (now - lastSpokenTimestamp) < 2000L) {
                return
            }
            // Cooldown against duplicate warnings
            if (text == lastSpokenMessage && (now - lastSpokenTimestamp) < 3500L) {
                return
            }
            lastSpokenMessage = text
            lastSpokenTimestamp = now
            currentPriority = SpeechPriority.SAFETY_WARNING
            if (!isTtsReady) return
            // Interrupt lower priority (Navigation / Info)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "SAFETY_${now}")
            return
        }

        // 3. Priority 3: NAVIGATION (Turn-by-turn maneuvers)
        if (priority == SpeechPriority.NAVIGATION) {
            // Do not interrupt active Emergency or Safety warnings
            if (isSpeakingActive && (currentPriority == SpeechPriority.EMERGENCY || currentPriority == SpeechPriority.SAFETY_WARNING) && (now - lastSpokenTimestamp) < 2500L) {
                return
            }
            if (text == lastSpokenMessage && (now - lastSpokenTimestamp) < 3000L) {
                return
            }
            lastSpokenMessage = text
            lastSpokenTimestamp = now
            currentPriority = SpeechPriority.NAVIGATION
            if (!isTtsReady) return
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "NAV_${now}")
            return
        }

        // 4. Priority 4: INFORMATION ("Path clear", spoken sparingly)
        if (priority == SpeechPriority.INFORMATION) {
            // Only speak if completely idle and sufficient silence has passed
            if (isSpeakingActive || (now - lastSpokenTimestamp) < 4500L) {
                return
            }
            lastSpokenMessage = text
            lastSpokenTimestamp = now
            currentPriority = SpeechPriority.INFORMATION
            if (!isTtsReady) return
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "INFO_${now}")
        }
    }

    override fun speakUrgent(text: String) {
        speakWithPriority(text, SpeechPriority.EMERGENCY)
    }

    override fun speakNormal(text: String) {
        speakWithPriority(text, SpeechPriority.SAFETY_WARNING)
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
