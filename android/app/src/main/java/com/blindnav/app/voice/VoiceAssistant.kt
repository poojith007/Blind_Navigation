package com.blindnav.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import com.blindnav.app.db.AppDatabase
import com.blindnav.app.db.UserPreferences
import com.blindnav.app.engine.IFeedbackEngine
import com.blindnav.app.location.LocationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class VoiceAssistant(
    private val context: Context,
    private val feedbackEngine: IFeedbackEngine,
    private val locationHelper: LocationHelper,
    private val database: AppDatabase,
    private val coroutineScope: CoroutineScope,
    private val onToggleDetection: (isPaused: Boolean) -> Unit,
    private val onSpeechRateChanged: (newRate: Float) -> Unit,
    private val onToggleTorch: (enable: Boolean) -> Unit,
    private val onCancelEmergency: () -> Unit,
    private val emergencyContactNumberProvider: () -> String?,
    private val onEmergencyContactUpdated: (newNumber: String) -> Unit,
    private val onToggleMapView: (() -> Unit)? = null,
    private val onRepeatNavigationDirection: (() -> Unit)? = null,
    private val onCancelNavigation: (() -> Unit)? = null,
    private val onNavigateToDestination: ((destination: String) -> Unit)? = null,
    private val onListeningStarted: (() -> Unit)? = null,
    private val onListeningStopped: (() -> Unit)? = null,
    private val onTriggerEmergencySos: (() -> Unit)? = null,
    private val onOpenEmergencySetup: (() -> Unit)? = null
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
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a command...")
        }

        try {
            onListeningStarted?.invoke()
            speechRecognizer?.startListening(intent)
            isListening = true
            feedbackEngine.speakNormal("Listening...")
        } catch (e: Exception) {
            e.printStackTrace()
            isListening = false
            onListeningStopped?.invoke()
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            isListening = false
            onListeningStopped?.invoke()
        }
        override fun onError(error: Int) {
            isListening = false
            onListeningStopped?.invoke()
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            onListeningStopped?.invoke()
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val command = matches[0]
                processCommand(command)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun processCommand(command: String) {
        when (val intent = VoiceCommandParser.parse(command)) {
            is VoiceIntent.CancelEmergency -> {
                onCancelEmergency()
            }
            is VoiceIntent.EmergencySos -> {
                if (onTriggerEmergencySos != null) {
                    onTriggerEmergencySos.invoke()
                } else {
                    sendEmergencySos("Voice SOS trigger activated.")
                }
            }
            is VoiceIntent.LocationInquiry -> {
                handleLocationInquiry()
            }
            is VoiceIntent.RepeatLast -> {
                feedbackEngine.repeatLastMessage()
            }
            is VoiceIntent.HistoryInquiry -> {
                handleHistoryInquiry()
            }
            is VoiceIntent.ToggleDetection -> {
                onToggleDetection(intent.pause)
                val msg = if (intent.pause) "Detection paused." else "Detection resumed."
                feedbackEngine.speakNormal(msg)
            }
            is VoiceIntent.AdjustSpeed -> {
                val delta = if (intent.faster) 0.2f else -0.2f
                val newRate = (feedbackEngine.currentSpeechRate + delta).coerceIn(0.6f, 2.2f)
                feedbackEngine.setSpeechRate(newRate)
                onSpeechRateChanged(newRate)
                persistPreferences()
                val msg = if (intent.faster) "Speech speed increased." else "Speech speed decreased."
                feedbackEngine.speakNormal(msg)
            }
            is VoiceIntent.SetEmergencyContact -> {
                val validation = com.blindnav.app.emergency.GuardianContactValidator.validate("Guardian", intent.phoneNumber)
                if (validation is com.blindnav.app.emergency.ValidationResult.Success) {
                    onEmergencyContactUpdated(intent.phoneNumber)
                    persistPreferences()
                    feedbackEngine.speakNormal("Emergency contact updated to ${intent.phoneNumber}.")
                } else if (validation is com.blindnav.app.emergency.ValidationResult.Error) {
                    feedbackEngine.speakUrgent("Cannot set contact: ${validation.reason}")
                }
            }
            is VoiceIntent.ToggleTorch -> {
                onToggleTorch(intent.enable)
                val msg = if (intent.enable) "Flashlight turned on." else "Flashlight turned off."
                feedbackEngine.speakNormal(msg)
            }
            is VoiceIntent.OpenMaps -> {
                feedbackEngine.speakNormal("Opening Google Maps walking navigation.")
                locationHelper.openInGoogleMaps()
            }
            is VoiceIntent.ToggleMap -> {
                onToggleMapView?.invoke()
                feedbackEngine.speakNormal("Switched navigation view mode.")
            }
            is VoiceIntent.NextDirection -> {
                onRepeatNavigationDirection?.invoke()
            }
            is VoiceIntent.StopNavigation -> {
                onCancelNavigation?.invoke()
                feedbackEngine.speakNormal("Walking navigation stopped.")
            }
            is VoiceIntent.NavigateTo -> {
                feedbackEngine.speakNormal("Searching for ${intent.destination}...")
                onNavigateToDestination?.invoke(intent.destination)
            }
            is VoiceIntent.StatusInquiry -> {
                feedbackEngine.speakNormal("System active. Camera scanning and AI detection running normally.")
            }
            is VoiceIntent.Help -> {
                feedbackEngine.speakNormal(
                    "You can say: Navigate to Central Park, Where am I, Next turn, Show map, Stop navigation, Repeat, History, Pause, Resume, Faster, Slower, Light on, or Emergency."
                )
            }
            is VoiceIntent.Unknown -> {
                feedbackEngine.speakNormal("Command not recognized: ${intent.rawText}. Say Help for options.")
            }
        }
    }

    private fun handleLocationInquiry() {
        val cachedLoc = locationHelper.lastLocation
        val cachedAddr = locationHelper.lastAddress
        if (cachedAddr != null) {
            val speedKmh = ((cachedLoc?.speed ?: 0f) * 3.6f).toInt()
            val speedInfo = if (speedKmh > 1) ", walking speed $speedKmh kilometers per hour" else ""
            feedbackEngine.speakNormal("You are currently near: $cachedAddr$speedInfo.")
            return
        }

        feedbackEngine.speakNormal("Acquiring GPS location...")
        locationHelper.getCurrentLocation { location, address, _ ->
            if (address != null) {
                val speedKmh = ((location?.speed ?: 0f) * 3.6f).toInt()
                val speedInfo = if (speedKmh > 1) ", walking speed $speedKmh kilometers per hour" else ""
                feedbackEngine.speakNormal("You are currently near: $address$speedInfo.")
            } else if (location != null) {
                val latFormatted = String.format(Locale.US, "%.4f", location.latitude)
                val lngFormatted = String.format(Locale.US, "%.4f", location.longitude)
                feedbackEngine.speakNormal("Coordinates: Latitude $latFormatted, Longitude $lngFormatted.")
            } else {
                feedbackEngine.speakNormal("Unable to determine GPS location. Please check location permissions and GPS signal.")
            }
        }
    }

    private fun handleHistoryInquiry() {
        coroutineScope.launch(Dispatchers.IO) {
            val logs = database.detectionDao().getRecentLogs()
            withContext(Dispatchers.Main) {
                if (logs.isEmpty()) {
                    feedbackEngine.speakNormal("No recent obstacles recorded.")
                } else {
                    val summary = logs.take(3).joinToString(separator = ". ") {
                        "${it.objectLabel} at ${String.format(Locale.US, "%.1f", it.distanceMeters)} meters"
                    }
                    feedbackEngine.speakNormal("Recent detections: $summary.")
                }
            }
        }
    }

    fun sendEmergencySos(customMessage: String) {
        val recipient = emergencyContactNumberProvider()
        if (recipient.isNullOrBlank()) {
            feedbackEngine.speakUrgent("Emergency alert failed. No emergency contact configured! Please configure your Guardian contact.")
            onOpenEmergencySetup?.invoke()
            return
        }

        feedbackEngine.speakUrgent("Preparing Emergency SOS Alert...")

        locationHelper.getCurrentLocation { location, address, mapsUrl ->
            val distressMsg = com.blindnav.app.emergency.EmergencySosHandler.formatEmergencyMessage(location, address, mapsUrl)
            val fullMessage = if (customMessage.isNotBlank()) "$distressMsg ($customMessage)" else distressMsg

            try {
                @Suppress("DEPRECATION")
                val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    SmsManager.getDefault()
                }
                val parts = smsManager.divideMessage(fullMessage)
                smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)

                feedbackEngine.speakUrgent("Emergency alert sent to Guardian at $recipient!")
                feedbackEngine.vibrateDanger()
            } catch (e: Exception) {
                e.printStackTrace()
                feedbackEngine.speakUrgent("Failed to send emergency SMS: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    private fun persistPreferences() {
        coroutineScope.launch(Dispatchers.IO) {
            val currentPrefs = database.detectionDao().getPreferences() ?: UserPreferences()
            val updated = currentPrefs.copy(
                speechRate = feedbackEngine.currentSpeechRate,
                emergencyContactPhone = emergencyContactNumberProvider() ?: ""
            )
            database.detectionDao().savePreferences(updated)
        }
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
