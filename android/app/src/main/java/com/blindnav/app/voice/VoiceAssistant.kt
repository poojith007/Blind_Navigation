package com.blindnav.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import com.blindnav.app.engine.FeedbackEngine
import java.util.Locale

class VoiceAssistant(
    private val context: Context,
    private val feedbackEngine: FeedbackEngine,
    private val emergencyContactNumberProvider: () -> String?
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }
        }
    }

    fun startListening() {
        if (isListening || speechRecognizer == null) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            feedbackEngine.speakNormal("Listening for command...")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { isListening = false }
        override fun onError(error: Int) { isListening = false }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val command = matches[0].lowercase()
                processCommand(command)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun processCommand(command: String) {
        when {
            command.contains("help") || command.contains("emergency") -> {
                sendEmergencySos("User requested emergency assistance via voice command.")
            }
            command.contains("where am i") || command.contains("location") -> {
                feedbackEngine.speakNormal("Fetching current location coordinates.")
            }
            command.contains("status") -> {
                feedbackEngine.speakNormal("System active. Camera feed and AI detection running.")
            }
            else -> {
                feedbackEngine.speakNormal("Command not recognized: $command")
            }
        }
    }

    fun sendEmergencySos(customMessage: String) {
        val recipient = emergencyContactNumberProvider()
        if (recipient.isNull_or_Empty_Fix()) {
            feedbackEngine.speakUrgent("Emergency alert failed. No emergency contact phone number configured!")
            return
        }

        try {
            val messageText = "EMERGENCY ALERT: Visually Impaired User needs assistance! $customMessage"
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(recipient, null, messageText, null, null)
            feedbackEngine.speakUrgent("Emergency alert SMS sent to $recipient!")
            feedbackEngine.vibrateDanger()
        } catch (e: Exception) {
            e.printStackTrace()
            feedbackEngine.speakUrgent("Failed to send emergency SMS.")
        }
    }

    private fun String?.isNull_or_Empty_Fix(): Boolean {
        return this == null || this.trim().isEmpty()
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
