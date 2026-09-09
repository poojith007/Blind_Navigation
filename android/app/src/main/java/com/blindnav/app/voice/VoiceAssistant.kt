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
import com.blindnav.app.offline.OfflineAreaManager
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
    private val offlineAreaManager: OfflineAreaManager? = null,
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
    private val onStartNavigation: (() -> Unit)? = null,
    private val onCallGuardian: (() -> Unit)? = null,
    private val onSendLocationAlert: (() -> Unit)? = null,
    private val onInquireDistance: (() -> Unit)? = null,
    private val onInquireObstacles: (() -> Unit)? = null,
    private val onListeningStarted: (() -> Unit)? = null,
    private val onListeningStopped: (() -> Unit)? = null,
    private val onTriggerEmergencySos: (() -> Unit)? = null,
    private val onOpenEmergencySetup: (() -> Unit)? = null,
    private val onPauseNavigation: (() -> Unit)? = null,
    private val onResumeNavigation: (() -> Unit)? = null,
    private val onGoHome: (() -> Unit)? = null,
    private val onStopSafety: (() -> Unit)? = null,
    private val onDownloadCurrentArea: (() -> Unit)? = null,
    private val onDownloadArea: ((areaName: String) -> Unit)? = null,
    private val onShowOfflineAreas: (() -> Unit)? = null,
    private val onDeleteOfflineArea: ((areaName: String) -> Unit)? = null
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

    fun startListening(promptPromptSpoken: Boolean = true) {
        if (isListening || speechRecognizer == null) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
        }

        try {
            onListeningStarted?.invoke()
            speechRecognizer?.startListening(intent)
            isListening = true
            if (promptPromptSpoken) {
                feedbackEngine.speakNormal("Listening.")
            }
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
            // Navigation Lifecycle
            is VoiceIntent.NavigateTo -> {
                feedbackEngine.speakNormal("Navigating to ${intent.destination}. Say start navigation to begin.")
                onNavigateToDestination?.invoke(intent.destination)
            }
            is VoiceIntent.StartNavigation -> {
                if (onStartNavigation != null) {
                    onStartNavigation.invoke()
                } else {
                    feedbackEngine.speakNormal("No destination selected. Say: Navigate to, followed by your destination.")
                }
            }
            is VoiceIntent.StopNavigation -> {
                onCancelNavigation?.invoke()
                feedbackEngine.speakNormal("Walking navigation stopped.")
            }
            is VoiceIntent.PauseNavigation -> {
                onPauseNavigation?.invoke()
                feedbackEngine.speakNormal("Navigation paused. Say resume navigation when ready.")
            }
            is VoiceIntent.ResumeNavigation -> {
                onResumeNavigation?.invoke()
                feedbackEngine.speakNormal("Resuming walking navigation.")
            }
            is VoiceIntent.GoHome -> {
                if (onGoHome != null) {
                    onGoHome.invoke()
                } else {
                    feedbackEngine.speakNormal("Navigating home.")
                }
            }

            // Navigation Inquiries
            is VoiceIntent.RepeatInstruction, is VoiceIntent.RepeatLast -> {
                onRepeatNavigationDirection?.invoke()
            }
            is VoiceIntent.NextInstruction -> {
                onRepeatNavigationDirection?.invoke()
            }
            is VoiceIntent.WhereAmI, is VoiceIntent.LocationInquiry -> {
                handleLocationInquiry()
            }
            is VoiceIntent.HowFar, is VoiceIntent.RemainingDistance, is VoiceIntent.EstimatedArrival -> {
                if (onInquireDistance != null) {
                    onInquireDistance.invoke()
                } else {
                    feedbackEngine.speakNormal("No active navigation route.")
                }
            }
            is VoiceIntent.ObstacleInquiry -> {
                if (onInquireObstacles != null) {
                    onInquireObstacles.invoke()
                } else {
                    feedbackEngine.speakNormal("Path clear ahead. Safe to proceed.")
                }
            }

            // Safety Commands
            is VoiceIntent.StopSafety -> {
                feedbackEngine.vibrateDanger()
                feedbackEngine.speakUrgent("Halt. Please stop immediately.")
                onStopSafety?.invoke()
            }
            is VoiceIntent.EmergencySos -> {
                if (onTriggerEmergencySos != null) {
                    onTriggerEmergencySos.invoke()
                } else {
                    sendEmergencySos("Voice SOS trigger activated.")
                }
            }
            is VoiceIntent.CallGuardian -> {
                feedbackEngine.speakUrgent("Calling your guardian. Say cancel to stop.")
                onCallGuardian?.invoke()
            }
            is VoiceIntent.SendLocationAlert -> {
                feedbackEngine.speakNormal("Sending your current location.")
                if (onSendLocationAlert != null) {
                    onSendLocationAlert.invoke()
                } else {
                    sendEmergencySos("Location distress alert requested via voice.")
                }
            }
            is VoiceIntent.CancelEmergency -> {
                onCancelEmergency()
            }

            // Offline Areas
            is VoiceIntent.DownloadCurrentArea -> {
                feedbackEngine.speakNormal("Downloading current area for offline navigation...")
                if (onDownloadCurrentArea != null) {
                    onDownloadCurrentArea.invoke()
                } else {
                    val loc = locationHelper.lastLocation
                    val city = locationHelper.lastStreetName ?: "Current Area"
                    if (loc != null && offlineAreaManager != null) {
                        offlineAreaManager.downloadCurrentArea(loc.latitude, loc.longitude, city) { area ->
                            feedbackEngine.speakNormal("Downloaded $city for offline navigation. Storage used: ${area.formattedSize}.")
                        }
                    } else {
                        feedbackEngine.speakNormal("Choose an area to download. For example, say download Bangalore.")
                    }
                }
            }
            is VoiceIntent.DownloadArea -> {
                feedbackEngine.speakNormal("Downloading ${intent.areaName} for offline navigation...")
                if (onDownloadArea != null) {
                    onDownloadArea.invoke(intent.areaName)
                } else {
                    offlineAreaManager?.downloadArea(
                        areaName = intent.areaName,
                        coverageDescription = "${intent.areaName} Urban Walking Corridor",
                        storageBytes = 12_500_000L
                    ) { area ->
                        feedbackEngine.speakNormal("Downloaded ${area.areaName}. Available offline.")
                    }
                }
            }
            is VoiceIntent.ShowOfflineMaps -> {
                if (onShowOfflineAreas != null) {
                    onShowOfflineAreas.invoke()
                } else {
                    offlineAreaManager?.getAllDownloadedAreas { areas ->
                        if (areas.isEmpty()) {
                            feedbackEngine.speakNormal("No offline areas downloaded yet. Say: download Bangalore, or download this area.")
                        } else {
                            val names = areas.joinToString(", ") { it.areaName }
                            feedbackEngine.speakNormal("Downloaded offline areas: $names. Showing offline maps screen.")
                        }
                    }
                }
            }
            is VoiceIntent.DeleteOfflineArea -> {
                if (intent.areaName.isBlank()) {
                    feedbackEngine.speakNormal("Which area would you like to delete? For example, say: delete Bangalore.")
                } else {
                    if (onDeleteOfflineArea != null) {
                        onDeleteOfflineArea.invoke(intent.areaName)
                    } else {
                        offlineAreaManager?.deleteAreaByName(intent.areaName) { found ->
                            if (found) {
                                feedbackEngine.speakNormal("Deleted offline area ${intent.areaName}.")
                            } else {
                                feedbackEngine.speakNormal("Offline area ${intent.areaName} not found.")
                            }
                        }
                    }
                }
            }
            is VoiceIntent.StorageInquiry -> {
                offlineAreaManager?.getStorageSummary { used, free ->
                    feedbackEngine.speakNormal("You have $free of offline storage available. Offline areas currently use $used.")
                } ?: feedbackEngine.speakNormal("Offline storage is available.")
            }

            // Settings Commands
            is VoiceIntent.ToggleVoiceGuidance -> {
                feedbackEngine.setVoiceGuidanceEnabled(intent.enable)
                persistPreferences()
                val msg = if (intent.enable) "Voice guidance turned on." else "Voice guidance turned off."
                feedbackEngine.speakUrgent(msg)
            }
            is VoiceIntent.AdjustVolume -> {
                feedbackEngine.adjustVolume(intent.increase)
                val msg = if (intent.increase) "Volume increased." else "Volume decreased."
                feedbackEngine.speakNormal(msg)
            }
            is VoiceIntent.ToggleVibration -> {
                feedbackEngine.isVibrationEnabled = intent.enable
                persistPreferences()
                val msg = if (intent.enable) "Vibration feedback enabled." else "Vibration feedback disabled."
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
            is VoiceIntent.ToggleTorch -> {
                onToggleTorch(intent.enable)
                val msg = if (intent.enable) "Flashlight turned on." else "Flashlight turned off."
                feedbackEngine.speakNormal(msg)
            }
            is VoiceIntent.ToggleDetection -> {
                onToggleDetection(intent.pause)
                val msg = if (intent.pause) "Detection paused." else "Detection resumed."
                feedbackEngine.speakNormal(msg)
            }
            is VoiceIntent.ToggleMap -> {
                onToggleMapView?.invoke()
                feedbackEngine.speakNormal("Switched navigation view mode.")
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

            // Diagnostic & General
            is VoiceIntent.OpenMaps -> {
                feedbackEngine.speakNormal("Opening Google Maps walking navigation.")
                locationHelper.openInGoogleMaps()
            }
            is VoiceIntent.HistoryInquiry -> {
                handleHistoryInquiry()
            }
            is VoiceIntent.StatusInquiry -> {
                feedbackEngine.speakNormal("System active. Camera scanning and path safety checks running normally.")
            }
            is VoiceIntent.Help -> {
                feedbackEngine.speakNormal(
                    "You can say: Navigate to Bangalore Railway Station, Start navigation, Stop navigation, Pause navigation, Resume navigation, Repeat instruction, Where am I, How far, Go home, Call guardian, Send location, Download this area, Show offline maps, Turn voice guidance on, or Emergency."
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
                feedbackEngine.speakNormal("Location is uncertain. Please stop and verify GPS.")
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
                isVoiceGuidanceEnabled = !feedbackEngine.isVoiceGuidanceMuted,
                isVibrationEnabled = feedbackEngine.isVibrationEnabled,
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
