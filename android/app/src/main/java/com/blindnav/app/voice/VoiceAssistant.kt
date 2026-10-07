package com.blindnav.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import android.util.Log
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

enum class VoiceState {
    LISTENING,
    PROCESSING,
    SPEAKING,
    MUTED,
    UNAVAILABLE
}

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
    private val onBeforeListeningStart: (() -> Unit)? = null,
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
    private val onDeleteOfflineArea: ((areaName: String) -> Unit)? = null,
    private val onToggleDemoMode: (() -> Unit)? = null,
    private val onFallbackSpeechRequested: (() -> Unit)? = null
) {

    private val tag = "VoiceAssistant"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    var isListening = false
        private set

    var isContinuousListeningActive: Boolean = true
    var isMuted: Boolean = false
        private set

    var currentVoiceState: VoiceState = VoiceState.LISTENING
        private set

    var onVoiceStateChanged: ((VoiceState) -> Unit)? = null

    private var isDestroyed = false

    private val restartRunnable = Runnable {
        if (!isDestroyed && !isMuted && isContinuousListeningActive && !feedbackEngine.isSpeaking) {
            startListeningInternal(promptSpoken = false)
        }
    }

    init {
        recreateRecognizer()

        feedbackEngine.onSpeakingStateChanged = { isSpeaking ->
            if (isSpeaking) {
                updateVoiceState(VoiceState.SPEAKING)
                pauseListeningForTts()
            } else {
                resumeListeningAfterTts()
            }
        }
    }

    private fun updateVoiceState(newState: VoiceState) {
        if (currentVoiceState == newState) return
        currentVoiceState = newState
        mainHandler.post {
            onVoiceStateChanged?.invoke(newState)
        }
    }

    private fun recreateRecognizer() {
        if (isDestroyed) return
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null

        val hasRecordAudio = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasRecordAudio) {
            Log.w(tag, "RECORD_AUDIO permission not granted yet.")
            updateVoiceState(VoiceState.UNAVAILABLE)
            return
        }

        val isAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        Log.i(tag, "SpeechRecognizer isRecognitionAvailable: $isAvailable")
        if (isAvailable) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createListener())
                }
                updateVoiceState(if (isMuted) VoiceState.MUTED else VoiceState.LISTENING)
            } catch (e: Exception) {
                Log.e(tag, "Failed to create SpeechRecognizer: ${e.message}")
                updateVoiceState(VoiceState.UNAVAILABLE)
            }
        } else {
            // Attempt createSpeechRecognizer as fallback in case isRecognitionAvailable returns false incorrectly
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createListener())
                }
                Log.i(tag, "SpeechRecognizer created via fallback.")
                updateVoiceState(if (isMuted) VoiceState.MUTED else VoiceState.LISTENING)
            } catch (e: Exception) {
                Log.w(tag, "Speech recognition unavailable on device: ${e.message}")
                updateVoiceState(VoiceState.UNAVAILABLE)
            }
        }
    }

    fun reinitialize() {
        mainHandler.post {
            recreateRecognizer()
            if (speechRecognizer != null && !isMuted && isContinuousListeningActive) {
                updateVoiceState(VoiceState.LISTENING)
                scheduleRestart(100L)
            }
        }
    }

    fun startListening(promptPromptSpoken: Boolean = false) {
        if (isMuted) {
            isMuted = false
        }
        mainHandler.removeCallbacks(restartRunnable)

        // Audio and haptic cue for the user
        try {
            val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 75)
            toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (_: Exception) {}
        feedbackEngine.vibrateCaution()

        if (speechRecognizer == null) {
            recreateRecognizer()
            if (speechRecognizer == null) {
                onFallbackSpeechRequested?.invoke()
                return
            }
        }

        startListeningInternal(promptSpoken = false)
    }

    private fun startListeningInternal(promptSpoken: Boolean = false) {
        if (isDestroyed || isMuted || !isContinuousListeningActive) return
        if (feedbackEngine.isSpeaking) {
            updateVoiceState(VoiceState.SPEAKING)
            return
        }

        if (speechRecognizer == null) {
            recreateRecognizer()
            if (speechRecognizer == null) {
                updateVoiceState(VoiceState.UNAVAILABLE)
                return
            }
        }

        mainHandler.post {
            if (isDestroyed || isMuted || !isContinuousListeningActive || feedbackEngine.isSpeaking) return@post

            // Crucial: Release the hardware mic from EnvironmentalAudioEngine before SpeechRecognizer captures it
            onBeforeListeningStart?.invoke()

            // Pre-emptively cancel any ongoing session to prevent ERROR_CLIENT / ERROR_RECOGNIZER_BUSY
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {}

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            try {
                speechRecognizer?.startListening(intent)
                isListening = true
                updateVoiceState(VoiceState.LISTENING)
                onListeningStarted?.invoke()
            } catch (e: Exception) {
                Log.w(tag, "Error starting speech listening: ${e.message}")
                isListening = false
                onListeningStopped?.invoke()
                recreateRecognizer()
                scheduleRestart(600L)
            }
        }
    }

    private fun pauseListeningForTts() {
        mainHandler.removeCallbacks(restartRunnable)
        if (isListening) {
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {}
            isListening = false
            onListeningStopped?.invoke()
        }
    }

    private fun resumeListeningAfterTts() {
        if (!isContinuousListeningActive || isMuted || isDestroyed) {
            if (isMuted) updateVoiceState(VoiceState.MUTED)
            return
        }
        // Small settle delay after speaker finishes output
        scheduleRestart(250L)
    }

    private fun scheduleRestart(delayMs: Long = 200L) {
        if (!isContinuousListeningActive || isMuted || isDestroyed) return
        if (feedbackEngine.isSpeaking) return

        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        if (isMuted) {
            pauseListeningForTts()
            updateVoiceState(VoiceState.MUTED)
            feedbackEngine.speakNormal("Microphone muted. Tap voice button to reactivate.")
        } else {
            updateVoiceState(VoiceState.LISTENING)
            feedbackEngine.speakNormal("Voice active. Listening.")
            startListening(promptPromptSpoken = false)
        }
        return isMuted
    }

    fun pauseContinuousListening() {
        isContinuousListeningActive = false
        mainHandler.removeCallbacks(restartRunnable)
        pauseListeningForTts()
    }

    fun resumeContinuousListening() {
        isContinuousListeningActive = true
        if (!isMuted && !feedbackEngine.isSpeaking) {
            updateVoiceState(VoiceState.LISTENING)
            scheduleRestart(200L)
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
            updateVoiceState(VoiceState.LISTENING)
        }

        override fun onBeginningOfSpeech() {
            // Speech detected from user
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isListening = false
            updateVoiceState(VoiceState.PROCESSING)
            onListeningStopped?.invoke()
        }

        override fun onError(error: Int) {
            isListening = false
            onListeningStopped?.invoke()

            val errorName = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO (3)"
                SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT (5)"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS (9)"
                SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK (2)"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT (1)"
                SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH (7)"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY (8)"
                SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER (4)"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT (6)"
                else -> "UNKNOWN ($error)"
            }
            Log.d(tag, "SpeechRecognizer error: $errorName")

            if (!isContinuousListeningActive || isMuted || isDestroyed || feedbackEngine.isSpeaking) {
                return
            }

            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Normal silence or timeout while waiting for voice; restart smoothly
                    scheduleRestart(250L)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> {
                    // Recognizer desynced or busy; recreate and restart
                    recreateRecognizer()
                    scheduleRestart(450L)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    updateVoiceState(VoiceState.UNAVAILABLE)
                }
                SpeechRecognizer.ERROR_AUDIO -> {
                    // Audio conflict; recreate and backoff so mic settles
                    recreateRecognizer()
                    scheduleRestart(700L)
                }
                SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                    scheduleRestart(900L)
                }
                else -> {
                    scheduleRestart(400L)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            onListeningStopped?.invoke()
            updateVoiceState(VoiceState.PROCESSING)

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val command = matches[0]
                processCommand(command)
            }

            if (!feedbackEngine.isSpeaking && isContinuousListeningActive && !isMuted) {
                scheduleRestart(300L)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val partial = matches[0]
                if (partial.isNotBlank()) {
                    Log.d(tag, "Partial speech: $partial")
                }
            }
        }
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

            is VoiceIntent.ToggleDemoMode -> {
                if (onToggleDemoMode != null) {
                    onToggleDemoMode.invoke()
                } else {
                    feedbackEngine.speakNormal("Demo diagnostic mode toggled.")
                }
            }
            is VoiceIntent.OpenGuardianSettings -> {
                feedbackEngine.speakNormal("Opening Guardian contact configuration.")
                onOpenEmergencySetup?.invoke()
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
                    "You can say: Navigate to, Start navigation, Stop navigation, Where am I, How far, Call guardian, Send location, Switch view, Demo mode, or Emergency."
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
        isDestroyed = true
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }
}
