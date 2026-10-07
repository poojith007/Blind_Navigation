package com.blindnav.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.speech.RecognizerIntent
import java.util.Locale
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.blindnav.app.camera.CameraXManager
import com.blindnav.app.db.AppDatabase
import com.blindnav.app.db.CachedRouteEntity
import com.blindnav.app.db.DetectionLog
import com.blindnav.app.db.UserPreferences
import com.blindnav.app.emergency.EmergencyContactRepository
import com.blindnav.app.emergency.EmergencyContactSetupDialog
import com.blindnav.app.emergency.EmergencySosDialog
import com.blindnav.app.emergency.EmergencySosHandler
import com.blindnav.app.engine.AudioEventType
import com.blindnav.app.engine.EnvironmentalAudioEngine
import com.blindnav.app.engine.FeedbackEngine
import com.blindnav.app.engine.SpatialSoundEngine
import com.blindnav.app.engine.SpeechPriority
import com.blindnav.app.engine.ThreatPrioritizer
import com.blindnav.app.location.GpsConfidence
import com.blindnav.app.location.InAppNavigationManager
import com.blindnav.app.location.LocationHelper
import com.blindnav.app.location.NavProgress
import com.blindnav.app.location.RoutePreview
import com.blindnav.app.ml.ObjectDetector
import com.blindnav.app.network.ConnectionStatus
import com.blindnav.app.network.OnlineOfflineManager
import com.blindnav.app.offline.OfflineAreaManager
import com.blindnav.app.offline.OfflineAreasDialog
import com.blindnav.app.sensors.FallDetector
import com.blindnav.app.sensors.ShakeDetector
import com.blindnav.app.ui.BoundingBoxOverlayView
import com.blindnav.app.ui.NavigationComponents
import com.blindnav.app.ui.NavigationUiState
import com.blindnav.app.ui.Palette
import com.blindnav.app.ui.dp
import com.blindnav.app.ui.pill
import com.blindnav.app.voice.VoiceAssistant
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MainActivity — rewritten UI layer.
 *
 * WHAT CHANGED vs. the previous version:
 *  - One [statusBanner] replaces five previously-separate, mostly always-on widgets
 *    (status pill, voice badge, system banner, obstacle alert card, main guidance card).
 *    [refreshBanner] picks exactly one message to show, by priority.
 *  - GPS / network / camera problems no longer fight for space in a permanent badge
 *    row; they're tracked as [gpsAlert]/[networkAlert]/[cameraAlert] and only shown
 *    in the banner while the problem is actually active.
 *  - The old code had THREE overlapping mechanisms for GPS/network status (a badge
 *    row, a manually-toggled banner keyed off raw accuracy, and unused enum states).
 *    This version has exactly one.
 *  - All dimensions are dp via UiKit's Context.dp()/dpf(), not raw pixels.
 *  - The walking-corridor overlay is demo-mode only (see BoundingBoxOverlayView).
 *
 * WHAT DID NOT CHANGE: every manager, engine, and business-logic call (camera, ML
 * detection, threat prioritization, voice commands, location/navigation, fall
 * detection, emergency SOS, offline caching) is wired exactly as it was.
 */
class MainActivity : AppCompatActivity() {

    private enum class ViewMode { SPLIT, CAMERA, MAP }
    private enum class NavState { IDLE, DESTINATION_SELECTED, NAVIGATING, ARRIVED }

    private var currentUiState = NavigationUiState.HOME
    private var currentNavState = NavState.IDLE
    private var isPerceptionDegraded = false

    private lateinit var rootLayout: FrameLayout
    private lateinit var mainContentLayout: LinearLayout
    private lateinit var cameraContainer: FrameLayout
    private lateinit var mapContainer: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: BoundingBoxOverlayView
    private lateinit var mapView: MapView
    private var myLocationOverlay: MyLocationNewOverlay? = null

    // Free dark basemap, no API key or billing account required — matches the app's
    // high-contrast theme in place of the old Google Maps dark style JSON.
    private val darkTileSource = object : XYTileSource(
        "CartoDBDarkMatter", 0, 20, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/dark_all/",
            "https://b.basemaps.cartocdn.com/dark_all/",
            "https://c.basemaps.cartocdn.com/dark_all/"
        )
    ) {}
    private lateinit var shakeDetector: ShakeDetector

    // --- The single status banner (replaces 5 old widgets) ---
    private lateinit var statusBanner: NavigationComponents.StatusBannerViews
    private var obstacleAlert: Triple<String, String, Int>? = null
    private var navPausedAlert: Triple<String, String, Int>? = null
    private var cameraAlert: Triple<String, String, Int>? = null
    private var gpsAlert: Triple<String, String, Int>? = null
    private var networkAlert: Triple<String, String, Int>? = null
    private var lastObstacleAlertTime = 0L

    // Demo / Examiner Mode UI
    private var isDemoMode = false
    private lateinit var demoDiagnosticCard: LinearLayout
    private lateinit var demoDiagnosticTextView: TextView

    // Search UI (IDLE State)
    private lateinit var searchContainer: LinearLayout
    private lateinit var searchEditText: EditText

    // Destination Preview Card (DESTINATION_SELECTED State)
    private lateinit var destinationPreviewCard: LinearLayout
    private lateinit var destinationTitleTextView: TextView
    private lateinit var destinationDetailsTextView: TextView
    private var currentPreview: RoutePreview? = null

    // Turn-by-turn Navigation HUD (NAVIGATING State)
    private lateinit var navHudLayout: LinearLayout
    private lateinit var navArrowTextView: TextView
    private lateinit var navInstructionTextView: TextView
    private lateinit var navNextStepTextView: TextView
    private lateinit var navDistanceTextView: TextView

    // Destination Reached Card (ARRIVED State)
    private lateinit var arrivedCard: LinearLayout
    private lateinit var arrivedDetailsTextView: TextView

    // Location & Controls
    private lateinit var locationTextView: TextView
    private lateinit var emergencyButton: Button
    private lateinit var mapHintTextView: TextView

    // Managers & Engines (unchanged business logic layer)
    private lateinit var cameraXManager: CameraXManager
    private lateinit var objectDetector: ObjectDetector
    private lateinit var feedbackEngine: FeedbackEngine
    private lateinit var spatialSoundEngine: SpatialSoundEngine
    private lateinit var threatPrioritizer: ThreatPrioritizer
    private lateinit var environmentalAudioEngine: EnvironmentalAudioEngine
    private lateinit var voiceAssistant: VoiceAssistant
    private lateinit var locationHelper: LocationHelper
    private lateinit var fallDetector: FallDetector
    private lateinit var database: AppDatabase
    private lateinit var onlineOfflineManager: OnlineOfflineManager
    private lateinit var offlineAreaManager: OfflineAreaManager
    private lateinit var accessibleVoiceButton: Button
    private val navigationManager = InAppNavigationManager()

    private var destinationMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var selectedPinLatLng: GeoPoint? = null
    private var currentViewMode = ViewMode.CAMERA
    private var isDetectionPaused = false
    private lateinit var emergencyRepository: EmergencyContactRepository
    private lateinit var emergencySosHandler: EmergencySosHandler
    private var emergencyContactPhone = ""
    private var lastDbLogTime = 0L

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.VIBRATE,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.SEND_SMS,
        Manifest.permission.CALL_PHONE
    )
    private val permissionRequestCode = 101

    private val speechRecognizerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val spokenText = matches[0]
                feedbackEngine.speakNormal("Recognized: $spokenText")
                voiceAssistant.processCommand(spokenText)
            }
        }
    }

    private fun launchSpeechRecognitionFallback() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command (e.g. 'Navigate to Central Park')")
            }
            speechRecognizerLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech recognition dialog unavailable: ${e.message}", Toast.LENGTH_LONG).show()
            feedbackEngine.speakUrgent("Speech recognition is not installed on this device. Please install Google Speech Services.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid setup — must happen before any MapView is created. Free, no API key,
        // no billing account. Tile cache lives in the app's own cache dir so no storage
        // permission is needed.
        Configuration.getInstance().apply {
            load(this@MainActivity, android.preference.PreferenceManager.getDefaultSharedPreferences(this@MainActivity))
            userAgentValue = packageName
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid")
        }

        locationHelper = LocationHelper(this)
        feedbackEngine = FeedbackEngine(this)
        spatialSoundEngine = SpatialSoundEngine(lifecycleScope)
        threatPrioritizer = ThreatPrioritizer(feedbackEngine, spatialSoundEngine)
        objectDetector = ObjectDetector(this)
        database = AppDatabase.getDatabase(this)
        emergencyRepository = EmergencyContactRepository(this, database, lifecycleScope)
        emergencySosHandler = EmergencySosHandler(this, feedbackEngine, locationHelper)
        emergencyContactPhone = emergencyRepository.getPrimaryGuardian()?.phoneNumber.orEmpty()
        offlineAreaManager = OfflineAreaManager(this, database, lifecycleScope)

        onlineOfflineManager = OnlineOfflineManager(this) { status, announcement ->
            runOnUiThread {
                networkAlert = if (status == ConnectionStatus.OFFLINE) {
                    Triple("📶", "Offline — on-device safety active", Palette.caution)
                } else null
                refreshBanner()
                if (announcement != null) feedbackEngine.speakNormal(announcement)
            }
        }
        onlineOfflineManager.startMonitoring()

        locationHelper.onGpsConfidenceChanged = { confidence, announcement ->
            runOnUiThread {
                gpsAlert = when (confidence) {
                    GpsConfidence.WEAK -> Triple("📡", "GPS signal weak — position estimated", Palette.caution)
                    GpsConfidence.UNAVAILABLE -> Triple("📡", "GPS unavailable — please stop", Palette.danger)
                    GpsConfidence.GOOD -> null
                }
                refreshBanner()
                if (announcement != null) feedbackEngine.speakNormal(announcement)
            }
        }

        setupAccessibleUiLayout(savedInstanceState)

        fallDetector = FallDetector(
            context = this,
            feedbackEngine = feedbackEngine,
            scope = lifecycleScope,
            onFallConfirmed = {
                if (emergencyRepository.hasGuardian()) {
                    emergencyRepository.getPrimaryGuardian()?.let { guardian ->
                        emergencySosHandler.sendLocationAlert(
                            contact = guardian,
                            backupContact = emergencyRepository.getBackupGuardian()
                        )
                    }
                } else {
                    feedbackEngine.speakUrgent("Fall detected! No emergency guardian configured. Please set up a Guardian.")
                    runOnUiThread { showEmergencySetupDialog() }
                }
            }
        )

        voiceAssistant = VoiceAssistant(
            context = this,
            feedbackEngine = feedbackEngine,
            locationHelper = locationHelper,
            database = database,
            coroutineScope = lifecycleScope,
            offlineAreaManager = offlineAreaManager,
            onToggleDetection = { paused ->
                isDetectionPaused = paused
                runOnUiThread { refreshBanner() }
            },
            onSpeechRateChanged = { newRate -> feedbackEngine.setSpeechRate(newRate) },
            onToggleTorch = { enable ->
                if (::cameraXManager.isInitialized) cameraXManager.enableTorch(enable)
            },
            onCancelEmergency = {
                runOnUiThread {
                    if (fallDetector.isCountdownActive) {
                        fallDetector.cancelFallAlert()
                    } else if (currentUiState == NavigationUiState.ROUTE_PREVIEW) {
                        cancelDestinationSelection()
                    } else if (navigationManager.isNavigating) {
                        stopWalkingNavigation()
                    } else {
                        feedbackEngine.speakNormal("No active action to cancel.")
                    }
                }
            },
            emergencyContactNumberProvider = { emergencyContactPhone },
            onEmergencyContactUpdated = { updatedNumber -> emergencyContactPhone = updatedNumber },
            onToggleMapView = { runOnUiThread { cycleViewMode() } },
            onRepeatNavigationDirection = { announceCurrentDirection() },
            onCancelNavigation = { runOnUiThread { stopWalkingNavigation() } },
            onNavigateToDestination = { destination ->
                runOnUiThread {
                    searchEditText.setText(destination)
                    executeDestinationSearch(destination)
                }
            },
            onStartNavigation = {
                runOnUiThread {
                    if (currentPreview != null || selectedPinLatLng != null) {
                        startWalkingNavigationFromPreview()
                    } else if (navigationManager.isNavigating) {
                        feedbackEngine.speakNormal("Already navigating to ${navigationManager.destinationName}.")
                    } else {
                        feedbackEngine.speakNormal("No destination selected. Say: Navigate to, followed by your destination.")
                    }
                }
            },
            onCallGuardian = {
                runOnUiThread {
                    val guardian = emergencyRepository.getPrimaryGuardian()
                    if (guardian != null) {
                        emergencySosHandler.callGuardian(guardian)
                    } else {
                        feedbackEngine.speakUrgent("No emergency contact configured! Please configure your Guardian contact.")
                        showEmergencySetupDialog()
                    }
                }
            },
            onSendLocationAlert = {
                runOnUiThread {
                    val guardian = emergencyRepository.getPrimaryGuardian()
                    if (guardian != null) {
                        emergencySosHandler.sendLocationAlert(guardian, emergencyRepository.getBackupGuardian())
                    } else {
                        feedbackEngine.speakUrgent("No emergency contact configured! Please configure your Guardian contact.")
                        showEmergencySetupDialog()
                    }
                }
            },
            onInquireDistance = {
                runOnUiThread {
                    if (navigationManager.isNavigating) {
                        val loc = locationHelper.lastLocation
                        if (loc != null) {
                            val progress = navigationManager.updateProgress(loc)
                            val totalStr = navigationManager.formatDistance(progress.totalRemainingDistanceMeters)
                            val step = progress.nextStep?.instruction ?: "Proceed straight"
                            feedbackEngine.speakNormal("$totalStr remaining to ${navigationManager.destinationName}, approximately ${progress.estimatedRemainingMinutes} minutes walk. Next: $step.")
                        } else {
                            feedbackEngine.speakNormal("Calculating remaining distance...")
                        }
                    } else {
                        feedbackEngine.speakNormal("No active navigation. Say: Navigate to, followed by your destination.")
                    }
                }
            },
            onInquireObstacles = {
                runOnUiThread {
                    val decision = threatPrioritizer.latestDecision
                    if (decision != null && decision.isObstaclePresent && decision.targetObject != null) {
                        feedbackEngine.speakNormal("Warning: ${decision.instruction}")
                    } else if (decision != null && decision.isPathUnclear) {
                        feedbackEngine.speakNormal("Path unclear. Please stop and verify surroundings.")
                    } else {
                        feedbackEngine.speakNormal("Path clear ahead. No obstacles in walking corridor.")
                    }
                }
            },
            onBeforeListeningStart = {
                if (::environmentalAudioEngine.isInitialized) environmentalAudioEngine.pauseListening()
            },
            onListeningStarted = {
                if (::environmentalAudioEngine.isInitialized) environmentalAudioEngine.pauseListening()
            },
            onListeningStopped = {
                if (::environmentalAudioEngine.isInitialized) environmentalAudioEngine.resumeListening()
            },
            onTriggerEmergencySos = { runOnUiThread { showEmergencySosDialog() } },
            onOpenEmergencySetup = { runOnUiThread { showEmergencySetupDialog() } },
            onPauseNavigation = {
                runOnUiThread {
                    navigationManager.pauseNavigation()
                    navPausedAlert = Triple("⏸", "Navigation paused — say resume", Palette.caution)
                    refreshBanner()
                }
            },
            onResumeNavigation = {
                runOnUiThread {
                    navigationManager.resumeNavigation()
                    navPausedAlert = null
                    updateUiForState(NavigationUiState.NAV_PATH_CLEAR)
                    announceCurrentDirection()
                }
            },
            onGoHome = {
                runOnUiThread {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val prefs = database.detectionDao().getPreferences()
                        if (prefs != null && prefs.homeAddress.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                searchEditText.setText(prefs.homeAddress)
                                executeDestinationSearch(prefs.homeAddress)
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                feedbackEngine.speakNormal("No home address configured. Say: Navigate to, followed by your destination.")
                            }
                        }
                    }
                }
            },
            onStopSafety = { runOnUiThread { if (navigationManager.isNavigating) stopWalkingNavigation() } },
            onDownloadCurrentArea = {
                runOnUiThread {
                    val loc = locationHelper.lastLocation
                    val name = locationHelper.lastStreetName ?: "Current Area"
                    if (loc != null) {
                        onlineOfflineManager.setTemporaryStatus(ConnectionStatus.DOWNLOADING)
                        feedbackEngine.speakNormal("Downloading current area $name for offline navigation.")
                        offlineAreaManager.downloadCurrentArea(loc.latitude, loc.longitude, name) { area ->
                            onlineOfflineManager.setTemporaryStatus(if (onlineOfflineManager.checkIsOnline()) ConnectionStatus.ONLINE else ConnectionStatus.OFFLINE)
                            feedbackEngine.speakNormal("Area ${area.areaName} is now available offline. Storage: ${area.formattedSize}.")
                        }
                    } else {
                        feedbackEngine.speakNormal("Acquiring GPS location before downloading current area. Please wait.")
                    }
                }
            },
            onDownloadArea = { areaName ->
                runOnUiThread {
                    onlineOfflineManager.setTemporaryStatus(ConnectionStatus.DOWNLOADING)
                    feedbackEngine.speakNormal("Downloading $areaName for offline navigation.")
                    offlineAreaManager.downloadArea(areaName, "$areaName Urban Walking Corridor", 12_500_000L) { area ->
                        onlineOfflineManager.setTemporaryStatus(if (onlineOfflineManager.checkIsOnline()) ConnectionStatus.ONLINE else ConnectionStatus.OFFLINE)
                        feedbackEngine.speakNormal("Area ${area.areaName} is now available offline.")
                    }
                }
            },
            onShowOfflineAreas = { runOnUiThread { showOfflineAreasDialog() } },
            onDeleteOfflineArea = { areaName ->
                runOnUiThread {
                    offlineAreaManager.deleteAreaByName(areaName) { deleted ->
                        feedbackEngine.speakNormal(
                            if (deleted) "Deleted offline area $areaName." else "Offline area $areaName not found."
                        )
                    }
                }
            },
            onToggleDemoMode = { runOnUiThread { toggleDemoMode() } },
            onFallbackSpeechRequested = { runOnUiThread { launchSpeechRecognitionFallback() } }
        )

        voiceAssistant.onVoiceStateChanged = { state ->
            runOnUiThread {
                if (::accessibleVoiceButton.isInitialized) {
                    NavigationComponents.updateVoiceButton(accessibleVoiceButton, state)
                }
            }
        }

        loadUserPreferences()

        if (allPermissionsGranted()) {
            initCameraAndServices()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions, permissionRequestCode)
        }

        setupGestureListener()
        fallDetector.start()
        shakeDetector = ShakeDetector(this) {
            feedbackEngine.vibrateCaution()
            runOnUiThread { voiceAssistant.startListening() }
        }
        shakeDetector.start()
    }

    private fun loadUserPreferences() {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = database.detectionDao().getPreferences()
            if (prefs != null) {
                if (prefs.emergencyContactPhone.isNotBlank() && emergencyContactPhone.isBlank()) {
                    emergencyContactPhone = prefs.emergencyContactPhone
                }
                withContext(Dispatchers.Main) {
                    feedbackEngine.setSpeechRate(prefs.speechRate)
                    feedbackEngine.isVoiceGuidanceMuted = !prefs.isVoiceGuidanceEnabled
                    feedbackEngine.isVibrationEnabled = prefs.isVibrationEnabled
                }
            } else {
                database.detectionDao().savePreferences(
                    UserPreferences(
                        userId = 1,
                        speechRate = 1.15f,
                        vibrationIntensity = 2,
                        emergencyContactPhone = emergencyContactPhone,
                        isVoiceGuidanceEnabled = true,
                        isVibrationEnabled = true
                    )
                )
            }
        }
    }

    // =====================================================================
    // UI CONSTRUCTION
    // =====================================================================

    private fun setupAccessibleUiLayout(savedInstanceState: Bundle?) {
        rootLayout = FrameLayout(this).apply {
            contentDescription = "Blind Navigation Main Screen. View split camera and live map. Double tap to pause detection. Long press to speak."
        }

        mainContentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // Camera
        cameraContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f)
        }
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cameraContainer.addView(previewView)
        overlayView = BoundingBoxOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cameraContainer.addView(overlayView)
        mainContentLayout.addView(cameraContainer)

        // Map
        mapContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f)
        }
        mapView = MapView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setTileSource(darkTileSource)
            setMultiTouchControls(true)
            setBuiltInZoomControls(true)
            controller.setZoom(17.5)
        }
        mapContainer.addView(mapView)
        setupOsmMap(mapView)

        mapHintTextView = TextView(this).apply {
            text = "📍 Tap or search above to set your walking destination"
            textSize = 12f
            setTextColor(Palette.white)
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
            }
        }
        mapContainer.addView(mapHintTextView)

        mainContentLayout.addView(mapContainer)
        rootLayout.addView(mainContentLayout)

        // Top overlay: banner + state-specific cards + location line
        val topOverlayContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(20), dp(12), 0)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        statusBanner = NavigationComponents.createStatusBanner(this)
        topOverlayContainer.addView(statusBanner.container)

        // Search bar (HOME / DESTINATION_SEARCH)
        searchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pill(this@MainActivity, Palette.cardBg, Palette.cardBorder, strokeWidthDp = 1, radiusDp = 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            elevation = dp(4).toFloat()
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(8))
            }
        }
        val searchIcon = TextView(this).apply { text = "🎙"; textSize = 16f; setPadding(0, 0, dp(10), 0) }
        searchContainer.addView(searchIcon)
        searchEditText = EditText(this).apply {
            hint = "Say: 'Navigate to [destination]'"
            setHintTextColor(Palette.muted)
            setTextColor(Palette.white)
            textSize = 15f
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            contentDescription = "Destination search input. Hands-free voice enabled. You can say: Navigate to, followed by any place name or address."
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    executeDestinationSearch(text.toString()); true
                } else false
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && currentUiState == NavigationUiState.HOME) updateUiForState(NavigationUiState.DESTINATION_SEARCH)
            }
        }
        searchContainer.addView(searchEditText)
        topOverlayContainer.addView(searchContainer)

        // Destination preview card (ROUTE_PREVIEW)
        destinationPreviewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(this@MainActivity, Palette.cardBg, Palette.info, strokeWidthDp = 1, radiusDp = 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(6).toFloat()
            visibility = View.GONE
            contentDescription = "Selected destination preview card."
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(8))
            }
        }
        destinationTitleTextView = TextView(this).apply {
            text = "Selected Destination"; textSize = 17f; setTextColor(Palette.white)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        destinationPreviewCard.addView(destinationTitleTextView)
        destinationDetailsTextView = TextView(this).apply {
            text = "Calculating walking route..."; textSize = 13f; setTextColor(Palette.muted)
            setPadding(0, dp(4), 0, dp(10))
        }
        destinationPreviewCard.addView(destinationDetailsTextView)

        val previewButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val startNavButton = Button(this).apply {
            text = "▶ Start"; textSize = 14f; setTextColor(Palette.white)
            background = pill(this@MainActivity, Color.parseColor("#00838F"), Palette.info, strokeWidthDp = 1, radiusDp = 20)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "Start walking navigation to selected destination."
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1.0f).apply { setMargins(0, 0, dp(8), 0) }
            setOnClickListener { startWalkingNavigationFromPreview() }
        }
        previewButtonsRow.addView(startNavButton)
        val cancelPreviewButton = Button(this).apply {
            text = "✕ Cancel"; textSize = 13f; setTextColor(Palette.white)
            background = pill(this@MainActivity, Color.parseColor("#2A2F38"), Palette.muted, strokeWidthDp = 1, radiusDp = 20)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "Cancel destination selection and return to search."
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(44))
            setOnClickListener { cancelDestinationSelection() }
        }
        previewButtonsRow.addView(cancelPreviewButton)
        destinationPreviewCard.addView(previewButtonsRow)
        topOverlayContainer.addView(destinationPreviewCard)

        // Nav HUD (NAVIGATING)
        navHudLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(this@MainActivity, Palette.cardBg, Palette.cardBorder, strokeWidthDp = 1, radiusDp = 16)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = dp(8).toFloat()
            visibility = View.GONE
            contentDescription = "Turn by turn walking instruction. Tap to hear directions aloud."
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(8))
            }
            setOnClickListener { announceCurrentDirection() }
        }
        val navManeuverRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        navArrowTextView = TextView(this).apply {
            text = "⬆"; textSize = 22f; setTextColor(Palette.white); gravity = Gravity.CENTER
            background = pill(this@MainActivity, Color.parseColor("#1B2430"), Palette.info, strokeWidthDp = 1, radiusDp = 12)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        navManeuverRow.addView(navArrowTextView)
        val navTextContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        navInstructionTextView = TextView(this).apply {
            text = "Walk straight"; textSize = 16f; setTextColor(Palette.white)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        navTextContainer.addView(navInstructionTextView)
        navNextStepTextView = TextView(this).apply {
            text = "Next: Follow path to destination"; textSize = 12f; setTextColor(Palette.info)
        }
        navTextContainer.addView(navNextStepTextView)
        navDistanceTextView = TextView(this).apply {
            text = "Calculating..."; textSize = 12f; setTextColor(Palette.muted)
        }
        navTextContainer.addView(navDistanceTextView)
        navManeuverRow.addView(navTextContainer)
        navHudLayout.addView(navManeuverRow)

        val stopNavButton = Button(this).apply {
            text = "⏹ Stop Navigation"; textSize = 14f; setTextColor(Palette.white)
            background = pill(this@MainActivity, Color.parseColor("#B71C1C"), Color.parseColor("#FF8A80"), strokeWidthDp = 1, radiusDp = 18)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "Stop walking navigation."
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { setMargins(0, dp(10), 0, 0) }
            setOnClickListener { stopWalkingNavigation() }
        }
        navHudLayout.addView(stopNavButton)
        topOverlayContainer.addView(navHudLayout)

        // Arrived card (ARRIVED)
        arrivedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(this@MainActivity, Palette.cardBg, Palette.safe, strokeWidthDp = 1, radiusDp = 16)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(8).toFloat()
            visibility = View.GONE
            contentDescription = "Destination reached banner."
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(8))
            }
        }
        val arrivedTitle = TextView(this).apply {
            text = "🎯 Arrived at destination"; textSize = 16f; setTextColor(Palette.safe)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        arrivedCard.addView(arrivedTitle)
        arrivedDetailsTextView = TextView(this).apply {
            text = "You have successfully reached your destination."; textSize = 13f
            setTextColor(Palette.white); setPadding(0, dp(4), 0, dp(10))
        }
        arrivedCard.addView(arrivedDetailsTextView)
        val arrivedDoneButton = Button(this).apply {
            text = "✔ Finish"; textSize = 14f; setTextColor(Palette.white)
            background = pill(this@MainActivity, Color.parseColor("#00838F"), Palette.safe, strokeWidthDp = 1, radiusDp = 18)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "Finish navigation and return to idle screen."
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
            setOnClickListener { cancelDestinationSelection() }
        }
        arrivedCard.addView(arrivedDoneButton)
        topOverlayContainer.addView(arrivedCard)

        // Quiet, slim location line (no card, no elevation — was previously a full glass card)
        locationTextView = TextView(this).apply {
            text = "📍 Locating..."
            textSize = 12f
            setTextColor(Palette.muted)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            contentDescription = "Current location. Tap to announce aloud."
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                val addr = locationHelper.lastAddress
                val loc = locationHelper.lastLocation
                if (addr != null) {
                    val bearing = if (loc != null && loc.hasBearing()) ", facing ${locationHelper.getCompassDirection(loc.bearing)}" else ""
                    feedbackEngine.speakNormal("Current location: $addr$bearing.")
                } else {
                    feedbackEngine.speakNormal("Acquiring GPS location. Please ensure you are under open sky.")
                }
            }
        }
        topOverlayContainer.addView(locationTextView)

        // Demo diagnostics (voice-toggled only)
        demoDiagnosticCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(this@MainActivity, Palette.cardBg, Palette.info, strokeWidthDp = 1, radiusDp = 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dp(6).toFloat()
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(8), 0, 0)
            }
        }
        demoDiagnosticTextView = TextView(this).apply {
            text = "🤖 DEMO: Vision idle | Audio active | Path clear"
            textSize = 11f; setTextColor(Palette.info)
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(0, 0, 0, dp(6))
        }
        demoDiagnosticCard.addView(demoDiagnosticTextView)

        val testRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun testBtn(label: String, type: AudioEventType): Button = Button(this).apply {
            text = label; textSize = 10f; setTextColor(Palette.white)
            background = pill(this@MainActivity, Color.parseColor("#1B2430"), Palette.cardBorder, strokeWidthDp = 1, radiusDp = 10)
            layoutParams = LinearLayout.LayoutParams(0, dp(30), 1.0f).apply { setMargins(dp(2), 0, dp(2), 0) }
            setOnClickListener {
                environmentalAudioEngine.simulateAudioEvent(type, 0.92f)
                Toast.makeText(this@MainActivity, "Simulated: ${type.name}", Toast.LENGTH_SHORT).show()
            }
        }
        testRow.addView(testBtn("Horn", AudioEventType.VEHICLE_HORN))
        testRow.addView(testBtn("Siren", AudioEventType.SIREN))
        testRow.addView(testBtn("Bell", AudioEventType.BICYCLE_BELL))
        testRow.addView(testBtn("Engine", AudioEventType.ENGINE_RUMBLE))
        demoDiagnosticCard.addView(testRow)
        topOverlayContainer.addView(demoDiagnosticCard)

        rootLayout.addView(topOverlayContainer)

        // Bottom: Voice + SOS
        val bottomActionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            elevation = dp(10).toFloat()
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                setMargins(dp(12), 0, dp(12), dp(18))
            }
        }
        accessibleVoiceButton = NavigationComponents.createVoiceButton(
            context = this,
            onClick = {
                if (voiceAssistant.isMuted) {
                    voiceAssistant.toggleMute()
                } else if (voiceAssistant.currentVoiceState == com.blindnav.app.voice.VoiceState.UNAVAILABLE) {
                    voiceAssistant.reinitialize()
                    rootLayout.postDelayed({
                        if (voiceAssistant.currentVoiceState == com.blindnav.app.voice.VoiceState.UNAVAILABLE) {
                            launchSpeechRecognitionFallback()
                        }
                    }, 350L)
                } else {
                    voiceAssistant.startListening()
                }
            },
            onLongClick = {
                voiceAssistant.toggleMute()
            }
        )
        emergencyButton = NavigationComponents.createEmergencyButton(
            context = this,
            onClick = {
                val guardian = emergencyRepository.getPrimaryGuardian()
                if (guardian != null) {
                    emergencySosHandler.callGuardian(guardian)
                } else {
                    feedbackEngine.speakUrgent("No emergency contact configured! Opening Guardian settings.")
                    showEmergencySetupDialog()
                }
            },
            onLongClick = { showEmergencySosDialog() }
        )
        bottomActionContainer.addView(emergencyButton)
        rootLayout.addView(bottomActionContainer)

        setContentView(rootLayout)

        // Seed initial GPS/network alert state, then draw the first banner.
        gpsAlert = when (locationHelper.currentGpsConfidence) {
            GpsConfidence.WEAK -> Triple("📡", "GPS signal weak — position estimated", Palette.caution)
            GpsConfidence.UNAVAILABLE -> Triple("📡", "GPS unavailable — please stop", Palette.danger)
            GpsConfidence.GOOD -> null
        }
        networkAlert = if (!onlineOfflineManager.checkIsOnline()) Triple("📶", "Offline — on-device safety active", Palette.caution) else null
        applyViewMode(ViewMode.CAMERA, announce = false)
        updateUiForState(NavigationUiState.HOME)
    }

    // =====================================================================
    // BANNER / STATE
    // =====================================================================

    /** The one place that decides which single message the banner shows. */
    private fun refreshBanner() {
        val (icon, message, color) = obstacleAlert
            ?: navPausedAlert
            ?: cameraAlert
            ?: gpsAlert
            ?: networkAlert
            ?: defaultBannerFor(currentUiState)
        NavigationComponents.updateStatusBanner(statusBanner, icon, message, color)
    }

    private fun defaultBannerFor(state: NavigationUiState): Triple<String, String, Int> = when (state) {
        NavigationUiState.HOME ->
            if (isDetectionPaused) Triple("⏸", "Detection paused", Palette.caution)
            else Triple("●", "Path clear — scanning", Palette.safe)
        NavigationUiState.DESTINATION_SEARCH -> Triple("🔍", "Searching destination", Palette.info)
        NavigationUiState.ROUTE_PREVIEW -> Triple("📍", "Route preview — say start or cancel", Palette.info)
        NavigationUiState.NAV_PATH_CLEAR -> Triple("●", "Path clear — navigating", Palette.safe)
        NavigationUiState.NAV_APPROACHING_TURN -> Triple("↗", "Approaching turn", Palette.info)
        NavigationUiState.DESTINATION_REACHED -> Triple("🎯", "Arrived at destination", Palette.safe)
        NavigationUiState.GUARDIAN_EMERGENCY -> Triple("🚨", "Emergency SOS active", Palette.danger)
        NavigationUiState.SETTINGS -> Triple("⚙", "Guardian settings", Palette.info)
        else -> Triple("●", "Path clear", Palette.safe) // OBSTACLE_WARNING/CRITICAL_STOP/PATH_UNCLEAR/GPS_WEAK/OFFLINE/CAMERA_UNAVAILABLE
        // are represented by obstacleAlert/gpsAlert/networkAlert/cameraAlert instead — currentUiState
        // is never actually set to these values (see updateUiForState below).
    }

    /**
     * Updates the "real" navigation state (which card is visible) and refreshes the banner.
     * Unlike the previous version, this never gets clobbered by a transient GPS/network
     * problem — those live entirely in [gpsAlert]/[networkAlert] and never touch [currentUiState].
     */
    private fun updateUiForState(state: NavigationUiState) {
        currentUiState = state
        currentNavState = when (state) {
            NavigationUiState.HOME, NavigationUiState.DESTINATION_SEARCH, NavigationUiState.SETTINGS -> NavState.IDLE
            NavigationUiState.ROUTE_PREVIEW -> NavState.DESTINATION_SELECTED
            NavigationUiState.NAV_PATH_CLEAR, NavigationUiState.NAV_APPROACHING_TURN,
            NavigationUiState.OBSTACLE_WARNING, NavigationUiState.CRITICAL_STOP,
            NavigationUiState.PATH_UNCLEAR -> NavState.NAVIGATING
            NavigationUiState.DESTINATION_REACHED -> NavState.ARRIVED
            NavigationUiState.GUARDIAN_EMERGENCY -> if (navigationManager.isNavigating) NavState.NAVIGATING else NavState.IDLE
            else -> currentNavState
        }

        runOnUiThread {
            when (state) {
                NavigationUiState.HOME, NavigationUiState.DESTINATION_SEARCH -> {
                    searchContainer.visibility = View.VISIBLE
                    destinationPreviewCard.visibility = View.GONE
                    navHudLayout.visibility = View.GONE
                    arrivedCard.visibility = View.GONE
                    mapHintTextView.text = "📍 Tap or search above to choose your destination"
                }
                NavigationUiState.ROUTE_PREVIEW -> {
                    searchContainer.visibility = View.GONE
                    destinationPreviewCard.visibility = View.VISIBLE
                    navHudLayout.visibility = View.GONE
                    arrivedCard.visibility = View.GONE
                    mapHintTextView.text = "📍 Route preview. Tap Start below."
                }
                NavigationUiState.NAV_PATH_CLEAR, NavigationUiState.NAV_APPROACHING_TURN,
                NavigationUiState.OBSTACLE_WARNING, NavigationUiState.CRITICAL_STOP,
                NavigationUiState.PATH_UNCLEAR -> {
                    searchContainer.visibility = View.GONE
                    destinationPreviewCard.visibility = View.GONE
                    navHudLayout.visibility = View.VISIBLE
                    arrivedCard.visibility = View.GONE
                    mapHintTextView.text = "📍 Navigating. Follow turn-by-turn guidance."
                }
                NavigationUiState.DESTINATION_REACHED -> {
                    searchContainer.visibility = View.GONE
                    destinationPreviewCard.visibility = View.GONE
                    navHudLayout.visibility = View.GONE
                    arrivedCard.visibility = View.VISIBLE
                    mapHintTextView.text = "🎯 Destination reached!"
                }
                else -> { /* GUARDIAN_EMERGENCY / SETTINGS handled by dialogs */ }
            }
            autoApplyViewModeForState(state)
            refreshBanner()
        }
    }

    private fun updateUiForState(state: NavState) {
        val uiState = when (state) {
            NavState.IDLE -> NavigationUiState.HOME
            NavState.DESTINATION_SELECTED -> NavigationUiState.ROUTE_PREVIEW
            NavState.NAVIGATING -> NavigationUiState.NAV_PATH_CLEAR
            NavState.ARRIVED -> NavigationUiState.DESTINATION_REACHED
        }
        updateUiForState(uiState)
    }

    // =====================================================================
    // MAP
    // =====================================================================

    private fun setupOsmMap(map: MapView) {
        enableMyLocationIfPermitted(map)

        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (currentNavState != NavState.NAVIGATING) handleMapPointSelected(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean {
                if (currentNavState != NavState.NAVIGATING) handleMapPointSelected(p)
                return true
            }
        })
        map.overlays.add(0, eventsOverlay)

        locationHelper.lastLocation?.let { loc ->
            map.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
            map.controller.setZoom(17.5)
        }
    }

    private fun enableMyLocationIfPermitted(map: MapView) {
        if (!allPermissionsGranted()) return
        try {
            myLocationOverlay?.let { map.overlays.remove(it) }
            val overlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map).apply { enableMyLocation() }
            myLocationOverlay = overlay
            map.overlays.add(overlay)
        } catch (e: SecurityException) { e.printStackTrace() }
    }

    private fun handleMapPointSelected(latLng: GeoPoint) {
        selectedPinLatLng = latLng
        destinationMarker?.let { mapView.overlays.remove(it) }

        val marker = Marker(mapView).apply {
            position = latLng
            title = "Selected Destination"
            snippet = "Calculating walking route..."
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        mapView.overlays.add(marker)
        destinationMarker = marker
        marker.showInfoWindow()
        mapView.invalidate()

        val currentLoc = locationHelper.lastLocation ?: Location("user").apply {
            latitude = latLng.latitude - 0.0020; longitude = latLng.longitude - 0.0020
        }
        val targetLoc = Location("pin").apply { latitude = latLng.latitude; longitude = latLng.longitude }

        lifecycleScope.launch(Dispatchers.IO) {
            val address = locationHelper.reverseGeocode(latLng.latitude, latLng.longitude)
            val placeName = address ?: "Selected Location"

            withContext(Dispatchers.Main) {
                val preview = navigationManager.previewRoute(currentLoc, targetLoc, placeName)
                currentPreview = preview
                drawRoutePreviewOnMap(currentLoc, preview)

                destinationTitleTextView.text = placeName
                val distStr = navigationManager.formatDistance(preview.totalDistanceMeters)
                destinationDetailsTextView.text = "Distance: $distStr  •  ~${preview.estimatedMinutes} mins walk"
                destinationMarker?.title = placeName
                destinationMarker?.snippet = "$distStr (${preview.estimatedMinutes} min walk)"
                mapView.invalidate()

                updateUiForState(NavigationUiState.ROUTE_PREVIEW)
                feedbackEngine.speakNormal("Destination selected: $placeName. Distance: $distStr. Estimated ${preview.estimatedMinutes} minutes walk. Say START to begin walking navigation, or say CANCEL.")
                lifecycleScope.launch(Dispatchers.Main) {
                    delay(4500L)
                    if (currentUiState == NavigationUiState.ROUTE_PREVIEW) voiceAssistant.startListening()
                }
            }
        }
    }

    private fun executeDestinationSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            Toast.makeText(this, "Please enter a destination name", Toast.LENGTH_SHORT).show()
            feedbackEngine.speakNormal("Please type or speak where you would like to go.")
            return
        }

        hideKeyboard()
        searchEditText.setText(trimmed)

        if (!onlineOfflineManager.checkIsOnline()) {
            feedbackEngine.speakNormal("Offline mode. Searching saved routes for $trimmed.")
            lifecycleScope.launch(Dispatchers.IO) {
                val cachedRoutes = database.detectionDao().getCachedRoutes()
                val match = cachedRoutes.firstOrNull { it.destinationName.contains(trimmed, ignoreCase = true) }
                if (match != null) {
                    withContext(Dispatchers.Main) {
                        val destLoc = Location("cache").apply { latitude = match.destinationLat; longitude = match.destinationLng }
                        val currentLoc = locationHelper.lastLocation ?: Location("user").apply {
                            latitude = match.destinationLat - 0.0020; longitude = match.destinationLng - 0.0020
                        }
                        val progress = navigationManager.startNavigationFromCache(match.destinationName, destLoc, match.routeJson, currentLoc)
                        drawRouteOnMap(currentLoc, destLoc)
                        updateNavHud(progress)
                        updateUiForState(NavState.NAVIGATING)
                        onlineOfflineManager.isNavigatingActive = true
                        val firstStep = progress.nextStep?.instruction ?: "Walk straight"
                        feedbackEngine.speakNormal("Starting offline navigation to ${match.destinationName}. Next: $firstStep. Continuing with local sensor guidance.")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        feedbackEngine.speakNormal("No offline route found for $trimmed. Please connect to the internet or open offline areas.")
                        Toast.makeText(this@MainActivity, "No offline route found for '$trimmed'", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }

        feedbackEngine.speakNormal("Searching for $trimmed...")

        locationHelper.searchDestination(trimmed) { targetLoc, resolvedName ->
            runOnUiThread {
                if (targetLoc != null) {
                    val displayName = resolvedName ?: trimmed
                    selectedPinLatLng = GeoPoint(targetLoc.latitude, targetLoc.longitude)
                    val currentLoc = locationHelper.lastLocation ?: Location("user").apply {
                        latitude = targetLoc.latitude - 0.0025; longitude = targetLoc.longitude - 0.0020
                    }
                    val preview = navigationManager.previewRoute(currentLoc, targetLoc, displayName)
                    currentPreview = preview

                    destinationMarker?.let { mapView.overlays.remove(it) }
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(targetLoc.latitude, targetLoc.longitude)
                        title = displayName
                        snippet = "${navigationManager.formatDistance(preview.totalDistanceMeters)} (${preview.estimatedMinutes} min walk)"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    mapView.overlays.add(marker)
                    destinationMarker = marker
                    marker.showInfoWindow()
                    mapView.invalidate()
                    drawRoutePreviewOnMap(currentLoc, preview)

                    destinationTitleTextView.text = displayName
                    val distStr = navigationManager.formatDistance(preview.totalDistanceMeters)
                    destinationDetailsTextView.text = "Distance: $distStr  •  ~${preview.estimatedMinutes} mins walk"

                    updateUiForState(NavigationUiState.ROUTE_PREVIEW)
                    feedbackEngine.speakNormal("Found $displayName. Distance: $distStr. Estimated ${preview.estimatedMinutes} minutes walk. Say START to begin walking navigation, or say CANCEL.")
                    lifecycleScope.launch(Dispatchers.Main) {
                        delay(4500L)
                        if (currentUiState == NavigationUiState.ROUTE_PREVIEW) voiceAssistant.startListening()
                    }
                } else {
                    feedbackEngine.speakNormal("Could not find location for $trimmed. Please check spelling or try a nearby street name.")
                    Toast.makeText(this@MainActivity, "Destination not found. Try another search.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun drawRoutePreviewOnMap(start: Location, preview: RoutePreview) {
        routePolyline?.let { mapView.overlays.remove(it) }
        val points = mutableListOf(GeoPoint(start.latitude, start.longitude))
        preview.routeSteps.forEach { points.add(GeoPoint(it.targetLocation.latitude, it.targetLocation.longitude)) }
        val polyline = Polyline(mapView).apply {
            setPoints(points)
            outlinePaint.color = Palette.info
            outlinePaint.strokeWidth = 12f
        }
        mapView.overlays.add(polyline)
        routePolyline = polyline
        try {
            val box = BoundingBox.fromGeoPoints(points)
            mapView.zoomToBoundingBox(box, true, 120)
        } catch (e: Exception) {
            mapView.controller.animateTo(GeoPoint(start.latitude, start.longitude))
            mapView.controller.setZoom(17.5)
        }
        mapView.invalidate()
    }

    private fun startWalkingNavigationFromPreview() {
        val preview = currentPreview
        if (preview != null) {
            val currentLoc = locationHelper.lastLocation ?: Location("user").apply {
                latitude = preview.destinationLocation.latitude - 0.0020
                longitude = preview.destinationLocation.longitude - 0.0020
            }
            startWalkingNavigationWithPreview(currentLoc, preview)
        } else {
            selectedPinLatLng?.let { pin ->
                val targetLoc = Location("pin").apply { latitude = pin.latitude; longitude = pin.longitude }
                startWalkingNavigation(targetLoc, "Selected Destination")
            }
        }
    }

    private fun startWalkingNavigationWithPreview(currentLoc: Location, preview: RoutePreview) {
        val progress = navigationManager.startNavigation(currentLoc, preview)
        drawRouteOnMap(currentLoc, preview.destinationLocation)
        updateNavHud(progress)
        updateUiForState(NavState.NAVIGATING)
        onlineOfflineManager.isNavigatingActive = true

        lifecycleScope.launch(Dispatchers.IO) {
            val stepsJson = navigationManager.serializeRouteToJson(preview.routeSteps)
            database.detectionDao().saveCachedRoute(
                CachedRouteEntity(
                    destinationName = preview.destinationName,
                    destinationLat = preview.destinationLocation.latitude,
                    destinationLng = preview.destinationLocation.longitude,
                    routeJson = stepsJson,
                    totalDistanceMeters = preview.totalDistanceMeters
                )
            )
        }

        val firstStep = progress.nextStep?.instruction ?: "Walk straight"
        val dist = progress.distanceToNextStepMeters.toInt()
        feedbackEngine.speakNormal("Starting walking navigation to ${preview.destinationName}. In $dist meters, $firstStep. You can say: Next turn, How far, or Stop navigation at any time.")
        mapView.controller.animateTo(GeoPoint(currentLoc.latitude, currentLoc.longitude))
        mapView.controller.setZoom(18.5)
    }

    private fun startWalkingNavigation(targetLocation: Location, targetName: String) {
        val currentLoc = locationHelper.lastLocation ?: Location("user").apply {
            latitude = targetLocation.latitude - 0.0020; longitude = targetLocation.longitude - 0.0020
        }
        val progress = navigationManager.startNavigation(currentLoc, targetLocation, targetName)
        drawRouteOnMap(currentLoc, targetLocation)
        updateNavHud(progress)
        updateUiForState(NavState.NAVIGATING)
        onlineOfflineManager.isNavigatingActive = true

        lifecycleScope.launch(Dispatchers.IO) {
            val stepsJson = navigationManager.serializeRouteToJson(navigationManager.currentSteps)
            database.detectionDao().saveCachedRoute(
                CachedRouteEntity(
                    destinationName = targetName,
                    destinationLat = targetLocation.latitude,
                    destinationLng = targetLocation.longitude,
                    routeJson = stepsJson,
                    totalDistanceMeters = progress.totalRemainingDistanceMeters
                )
            )
        }

        val firstStep = progress.nextStep?.instruction ?: "Walk straight"
        val dist = progress.distanceToNextStepMeters.toInt()
        feedbackEngine.speakNormal("Starting walking navigation to $targetName. In $dist meters, $firstStep. You can say: Next turn, How far, or Stop navigation at any time.")
        mapView.controller.animateTo(GeoPoint(currentLoc.latitude, currentLoc.longitude))
        mapView.controller.setZoom(18.5)
    }

    private fun stopWalkingNavigation() {
        navigationManager.stopNavigation()
        onlineOfflineManager.isNavigatingActive = false
        routePolyline?.let { mapView.overlays.remove(it) }; routePolyline = null
        destinationMarker?.let { mapView.overlays.remove(it) }; destinationMarker = null
        mapView.invalidate()
        selectedPinLatLng = null
        currentPreview = null
        navPausedAlert = null
        updateUiForState(NavState.IDLE)
        feedbackEngine.speakNormal("Walking navigation stopped.")
    }

    private fun cancelDestinationSelection() {
        routePolyline?.let { mapView.overlays.remove(it) }; routePolyline = null
        destinationMarker?.let { mapView.overlays.remove(it) }; destinationMarker = null
        mapView.invalidate()
        selectedPinLatLng = null
        currentPreview = null
        updateUiForState(NavState.IDLE)
        feedbackEngine.speakNormal("Destination selection canceled.")
    }

    // =====================================================================
    // DIALOGS
    // =====================================================================

    private fun showOfflineAreasDialog() {
        OfflineAreasDialog(
            context = this,
            offlineAreaManager = offlineAreaManager,
            feedbackEngine = feedbackEngine,
            onDownloadCurrentArea = {
                val loc = locationHelper.lastLocation
                val name = locationHelper.lastStreetName ?: "Current Area"
                if (loc != null) {
                    onlineOfflineManager.setTemporaryStatus(ConnectionStatus.DOWNLOADING)
                    feedbackEngine.speakNormal("Downloading current area $name for offline navigation.")
                    offlineAreaManager.downloadCurrentArea(loc.latitude, loc.longitude, name) { area ->
                        onlineOfflineManager.setTemporaryStatus(if (onlineOfflineManager.checkIsOnline()) ConnectionStatus.ONLINE else ConnectionStatus.OFFLINE)
                        feedbackEngine.speakNormal("Area ${area.areaName} is now available offline. Storage: ${area.formattedSize}.")
                    }
                } else {
                    feedbackEngine.speakNormal("Acquiring GPS location before downloading current area. Please wait.")
                }
            },
            onDownloadNewArea = { feedbackEngine.speakNormal("Please say: Download area, followed by the city name.") }
        ).show()
    }

    private fun showEmergencySosDialog() {
        val prevState = currentUiState
        updateUiForState(NavigationUiState.GUARDIAN_EMERGENCY)
        if (emergencyRepository.hasGuardian()) {
            EmergencySosDialog(
                context = this,
                repository = emergencyRepository,
                emergencySosHandler = emergencySosHandler,
                feedbackEngine = feedbackEngine,
                onOpenSettings = { showEmergencySetupDialog() },
                onDismiss = { updateUiForState(prevState) }
            ).show()
        } else {
            feedbackEngine.speakUrgent("No emergency contact configured! Please configure your Guardian contact.")
            showEmergencySetupDialog()
        }
    }

    private fun showEmergencySetupDialog() {
        val prevState = currentUiState
        updateUiForState(NavigationUiState.SETTINGS)
        EmergencyContactSetupDialog(
            context = this,
            repository = emergencyRepository,
            feedbackEngine = feedbackEngine,
            onSaved = { config -> emergencyContactPhone = config.primaryGuardian?.phoneNumber.orEmpty() },
            onDismiss = { updateUiForState(prevState) }
        ).show()
    }

    private fun toggleDemoMode() {
        isDemoMode = !isDemoMode
        overlayView.isDemoModeEnabled = isDemoMode
        demoDiagnosticCard.visibility = if (isDemoMode) View.VISIBLE else View.GONE
        feedbackEngine.speakNormal(
            if (isDemoMode) "Demo mode enabled. AI bounding boxes and multi-sensor diagnostics active."
            else "Demo mode disabled. Calm assistive path guidance active."
        )
    }

    private fun cycleViewMode() {
        currentViewMode = when (currentViewMode) {
            ViewMode.SPLIT -> ViewMode.CAMERA
            ViewMode.CAMERA -> ViewMode.MAP
            ViewMode.MAP -> ViewMode.SPLIT
        }
        applyViewMode(currentViewMode)
    }

    private fun applyViewMode(mode: ViewMode, announce: Boolean = true) {
        when (mode) {
            ViewMode.SPLIT -> {
                cameraContainer.visibility = View.VISIBLE
                (cameraContainer.layoutParams as LinearLayout.LayoutParams).weight = 2.0f
                mapContainer.visibility = View.VISIBLE
                (mapContainer.layoutParams as LinearLayout.LayoutParams).weight = 1.0f
                if (announce) feedbackEngine.speakNormal("Split screen mode active. Showing camera and live map.")
            }
            ViewMode.CAMERA -> {
                cameraContainer.visibility = View.VISIBLE
                (cameraContainer.layoutParams as LinearLayout.LayoutParams).weight = 1.0f
                mapContainer.visibility = View.GONE
                if (announce) feedbackEngine.speakNormal("Camera mode active.")
            }
            ViewMode.MAP -> {
                cameraContainer.visibility = View.GONE
                mapContainer.visibility = View.VISIBLE
                (mapContainer.layoutParams as LinearLayout.LayoutParams).weight = 1.0f
                if (announce) feedbackEngine.speakNormal("Full map mode active.")
            }
        }
        mainContentLayout.requestLayout()
    }

    /**
     * Automatically switches between full-screen camera (clean, default) and a
     * camera-dominant split (map visible for route context) based on navigation
     * state. Never overrides a user's explicit "show map" full-map-mode request.
     */
    private fun autoApplyViewModeForState(state: NavigationUiState) {
        if (currentViewMode == ViewMode.MAP) return
        val target = when (state) {
            NavigationUiState.HOME, NavigationUiState.DESTINATION_SEARCH, NavigationUiState.DESTINATION_REACHED -> ViewMode.CAMERA
            NavigationUiState.ROUTE_PREVIEW, NavigationUiState.NAV_PATH_CLEAR, NavigationUiState.NAV_APPROACHING_TURN -> ViewMode.SPLIT
            else -> return
        }
        if (currentViewMode != target) {
            currentViewMode = target
            applyViewMode(target, announce = false)
        }
    }

    private fun drawRouteOnMap(start: Location, target: Location) {
        routePolyline?.let { mapView.overlays.remove(it) }
        val points = mutableListOf(GeoPoint(start.latitude, start.longitude))
        if (navigationManager.currentRoute.isNotEmpty()) {
            navigationManager.currentRoute.forEach { points.add(GeoPoint(it.targetLocation.latitude, it.targetLocation.longitude)) }
        } else {
            points.add(GeoPoint(target.latitude, target.longitude))
        }
        val polyline = Polyline(mapView).apply {
            setPoints(points)
            outlinePaint.color = Palette.info
            outlinePaint.strokeWidth = 12f
        }
        mapView.overlays.add(polyline)
        routePolyline = polyline
        mapView.controller.animateTo(GeoPoint(start.latitude, start.longitude))
        mapView.controller.setZoom(18.5)
        mapView.invalidate()
    }

    private fun updateNavHud(progress: NavProgress) {
        if (!navigationManager.isNavigating) {
            if (progress.isArrived) handleArrival() else navHudLayout.visibility = View.GONE
            return
        }
        if (progress.isArrived) { handleArrival(); return }

        navHudLayout.visibility = View.VISIBLE
        navArrowTextView.text = progress.directionSymbol
        val step = progress.nextStep
        if (step != null) {
            val dist = progress.distanceToNextStepMeters.toInt()
            navInstructionTextView.text = "In ${dist}m: ${step.instruction}"
            navNextStepTextView.text = progress.upcomingStep?.let { "Next: ${it.instruction}" } ?: "Follow path to destination"
            navDistanceTextView.text = "${navigationManager.formatDistance(progress.totalRemainingDistanceMeters)} remaining  •  ~${progress.estimatedRemainingMinutes} mins"
        }
    }

    private fun handleArrival() {
        val destName = navigationManager.destinationName.ifBlank { "your destination" }
        arrivedDetailsTextView.text = "You have reached $destName. Walking navigation complete."
        updateUiForState(NavState.ARRIVED)
        feedbackEngine.speakNormal("You have arrived at your destination, $destName. Walking navigation complete.")
        feedbackEngine.vibrateCaution()
    }

    private fun announceCurrentDirection() {
        if (!navigationManager.isNavigating) {
            feedbackEngine.speakNormal("No active walking navigation. Type a destination or tap on the map to set one.")
            return
        }
        val loc = locationHelper.lastLocation ?: return
        val progress = navigationManager.updateProgress(loc)
        val step = progress.nextStep
        if (step != null) {
            val dist = progress.distanceToNextStepMeters.toInt()
            val totalStr = navigationManager.formatDistance(progress.totalRemainingDistanceMeters)
            feedbackEngine.speakNormal("In $dist meters, ${step.instruction}. Total remaining distance: $totalStr, approximately ${progress.estimatedRemainingMinutes} minutes walk.")
        } else if (progress.isArrived) {
            feedbackEngine.speakNormal("You have arrived at your destination.")
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        currentFocus?.let { view -> imm?.hideSoftInputFromWindow(view.windowToken, 0) }
    }

    // =====================================================================
    // CAMERA / DETECTION LOOP
    // =====================================================================

    private fun initCameraAndServices() {
        environmentalAudioEngine = EnvironmentalAudioEngine(this) { evidence ->
            runOnUiThread {
                if (isDemoMode) {
                    demoDiagnosticTextView.text = "🔊 Acoustic: ${evidence.type.name} (${(evidence.confidence * 100).toInt()}%)"
                }
            }
        }
        environmentalAudioEngine.start()

        cameraXManager = CameraXManager(
            context = this,
            lifecycleOwner = this,
            previewView = previewView,
            onLowLightDetected = { isDark ->
                isPerceptionDegraded = isDark
                if (isDark) {
                    runOnUiThread { feedbackEngine.speakNormal("Low light detected. Consider saying: Light on, to improve visibility.") }
                }
            },
            onFrameAnalyzed = { bitmap ->
                if (isDetectionPaused) return@CameraXManager

                val detections = objectDetector.detect(bitmap)
                val audioEvidence = environmentalAudioEngine.getRecentAudioEvidence()
                val loc = locationHelper.lastLocation
                val navProgress = if (navigationManager.isNavigating && loc != null) navigationManager.updateProgress(loc) else null

                val decision = threatPrioritizer.processFrameDetections(
                    detections = detections,
                    audioEvidence = audioEvidence,
                    navProgress = navProgress,
                    isUserMoving = (loc?.speed ?: 0f) > 0.3f,
                    isPerceptionDegraded = isPerceptionDegraded
                )

                runOnUiThread {
                    overlayView.updateDetections(detections)
                    val now = System.currentTimeMillis()

                    if (decision != null && decision.isPathUnclear) {
                        lastObstacleAlertTime = now
                        obstacleAlert = Triple("❓", decision.instruction.ifBlank { "Path unclear — please stop" }, Palette.caution)
                        updateUiForState(NavigationUiState.PATH_UNCLEAR)
                    } else if (decision != null && decision.isObstaclePresent && decision.targetObject != null) {
                        lastObstacleAlertTime = now
                        val isDanger = decision.priority == SpeechPriority.EMERGENCY
                        val icon = if (isDanger) "🛑" else "⚠"
                        val color = if (isDanger) Palette.danger else Palette.caution
                        obstacleAlert = Triple(icon, decision.instruction, color)
                        updateUiForState(if (isDanger) NavigationUiState.CRITICAL_STOP else NavigationUiState.OBSTACLE_WARNING)
                    } else if (now - lastObstacleAlertTime > 3000L) {
                        obstacleAlert = null
                        if (navigationManager.isNavigating) {
                            val isNearTurn = (navProgress?.distanceToNextStepMeters ?: 100f) <= 25f
                            updateUiForState(if (isNearTurn) NavigationUiState.NAV_APPROACHING_TURN else NavigationUiState.NAV_PATH_CLEAR)
                        } else {
                            updateUiForState(NavigationUiState.HOME)
                        }
                    }

                    if (isDemoMode) {
                        val corridorCount = detections.count { it.isInCorridor }
                        val audioStr = audioEvidence?.let { "${it.type.name} (${(it.confidence * 100).toInt()}%)" } ?: "Quiet"
                        val topObjStr = detections.firstOrNull()?.let { "${it.label} ${it.distanceMeters}m" } ?: "None"
                        demoDiagnosticTextView.text = "🤖 DEMO: ${objectDetector.lastInferenceMs}ms | ${detections.size} objs ($corridorCount in path) | Top: $topObjStr | Audio: $audioStr"
                    }
                }

                logDetectionsToDatabase(detections)
            }
        )

        cameraXManager.startCamera()

        locationHelper.startContinuousTracking(
            intervalMs = 3500L,
            minDisplacementMeters = 2.0f,
            onLocationUpdate = { loc, address, streetChanged ->
                runOnUiThread {
                    val bearingStr = if (loc.hasBearing()) " | ${locationHelper.getCompassDirection(loc.bearing)}" else ""
                    val speedKmh = (loc.speed * 3.6f).toInt()
                    val speedStr = if (speedKmh > 1) " | $speedKmh km/h" else ""
                    locationTextView.text = "📍 $address$bearingStr$speedStr"
                    locationTextView.contentDescription = "Current location: $address. Tap to announce."

                    if (currentNavState == NavState.NAVIGATING) {
                        mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                        // Note: unlike the old Google Maps camera, osmdroid map rotation-to-bearing
                        // was left out for now (cosmetic only) to keep this swap low-risk before the
                        // demo. The map still recenters smoothly on the user as they walk.

                        val progress = navigationManager.updateProgress(loc)
                        updateNavHud(progress)
                        navigationManager.shouldAnnounceDirection(progress)?.let { feedbackEngine.speakNormal(it) }
                    } else if (currentNavState == NavState.IDLE) {
                        mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                    }
                }

                if (streetChanged && currentNavState != NavState.NAVIGATING) {
                    locationHelper.lastStreetName?.let { street -> feedbackEngine.speakNormal("Now walking near $street.") }
                }
            }
        )

        feedbackEngine.speakNormal("Blind Navigation System Ready. Camera, Map, and Voice are Active and Listening.")
        if (::voiceAssistant.isInitialized) {
            voiceAssistant.reinitialize()
            voiceAssistant.resumeContinuousListening()
        }
    }

    private fun logDetectionsToDatabase(detections: List<com.blindnav.app.ml.DetectedObject>) {
        if (detections.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastDbLogTime < 3000L) return
        lastDbLogTime = now

        lifecycleScope.launch(Dispatchers.IO) {
            val top = detections.first()
            database.detectionDao().insertLog(
                DetectionLog(objectLabel = top.label, confidence = top.confidence, distanceMeters = top.distanceMeters, threatLevel = top.threatLevel.name)
            )
        }
    }

    private fun setupGestureListener() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (fallDetector.isCountdownActive) { fallDetector.cancelFallAlert(); return true }
                return super.onSingleTapConfirmed(e)
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (fallDetector.isCountdownActive) { fallDetector.cancelFallAlert(); return true }
                isDetectionPaused = !isDetectionPaused
                val msg = if (isDetectionPaused) "Detection paused." else "Detection resumed."
                feedbackEngine.speakNormal(msg)
                refreshBanner()
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                feedbackEngine.vibrateCaution()
                voiceAssistant.startListening()
            }
        })

        val touchListener = View.OnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
        rootLayout.setOnTouchListener(touchListener)
        previewView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event); true }
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            if (allPermissionsGranted()) {
                initCameraAndServices()
                enableMyLocationIfPermitted(mapView)
                if (::voiceAssistant.isInitialized) {
                    voiceAssistant.reinitialize()
                }
            } else {
                Toast.makeText(this, "Permissions required for Blind Nav App", Toast.LENGTH_LONG).show()
                feedbackEngine.speakUrgent("Camera, Location, and SMS permissions are required for full system functionality.")
            }
        }
    }

    override fun onStart() { super.onStart() }
    override fun onResume() {
        super.onResume(); mapView.onResume()
        if (::voiceAssistant.isInitialized) voiceAssistant.resumeContinuousListening()
    }
    override fun onPause() {
        super.onPause(); mapView.onPause()
        if (::voiceAssistant.isInitialized) voiceAssistant.pauseContinuousListening()
    }
    override fun onStop() { super.onStop() }
    override fun onLowMemory() { super.onLowMemory() }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDetach()
        fallDetector.stop()
        if (::shakeDetector.isInitialized) shakeDetector.stop()
        if (::locationHelper.isInitialized) locationHelper.stopContinuousTracking()
        if (::cameraXManager.isInitialized) cameraXManager.shutdown()
        if (::objectDetector.isInitialized) objectDetector.close()
        if (::feedbackEngine.isInitialized) feedbackEngine.shutdown()
        if (::voiceAssistant.isInitialized) voiceAssistant.destroy()
        if (::environmentalAudioEngine.isInitialized) environmentalAudioEngine.stop()
        if (::onlineOfflineManager.isInitialized) onlineOfflineManager.stopMonitoring()
    }
}