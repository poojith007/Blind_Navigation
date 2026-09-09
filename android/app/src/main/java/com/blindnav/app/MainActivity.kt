package com.blindnav.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.blindnav.app.camera.CameraXManager
import com.blindnav.app.db.AppDatabase
import com.blindnav.app.db.DetectionLog
import com.blindnav.app.db.UserPreferences
import com.blindnav.app.engine.FeedbackEngine
import com.blindnav.app.engine.SpatialSoundEngine
import com.blindnav.app.engine.ThreatPrioritizer
import com.blindnav.app.location.InAppNavigationManager
import com.blindnav.app.location.LocationHelper
import com.blindnav.app.location.NavProgress
import com.blindnav.app.ml.ObjectDetector
import com.blindnav.app.sensors.FallDetector
import com.blindnav.app.ui.BoundingBoxOverlayView
import com.blindnav.app.voice.VoiceAssistant
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import com.blindnav.app.location.RoutePreview
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

import com.blindnav.app.engine.EnvironmentalAudioEngine
import com.blindnav.app.sensors.ShakeDetector
import com.blindnav.app.engine.AudioEventType
import com.blindnav.app.engine.AudioEvidence
import com.blindnav.app.engine.GuidanceDecision
import com.blindnav.app.engine.SpeechPriority
import com.blindnav.app.emergency.EmergencyContactRepository
import com.blindnav.app.emergency.EmergencyContactSetupDialog
import com.blindnav.app.emergency.EmergencySosDialog
import com.blindnav.app.emergency.EmergencySosHandler
import com.blindnav.app.ui.NavigationUiState
import com.blindnav.app.ui.NavigationComponents
import com.blindnav.app.db.CachedRouteEntity
import com.blindnav.app.location.GpsConfidence
import com.blindnav.app.network.ConnectionStatus
import com.blindnav.app.network.OnlineOfflineManager
import com.blindnav.app.offline.OfflineAreaManager
import com.blindnav.app.offline.OfflineAreasDialog

class MainActivity : AppCompatActivity() {

    private enum class ViewMode {
        SPLIT, CAMERA, MAP
    }

    private enum class NavState {
        IDLE, DESTINATION_SELECTED, NAVIGATING, ARRIVED
    }

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
    private var googleMap: GoogleMap? = null
    private lateinit var shakeDetector: ShakeDetector

    // Status, Main Guidance & Obstacle Alerts
    private lateinit var statusTextView: TextView
    private lateinit var systemBanner: TextView
    private lateinit var mainGuidanceCard: LinearLayout
    private lateinit var mainGuidanceTextView: TextView
    private lateinit var obstacleAlertCard: LinearLayout
    private lateinit var obstacleAlertIcon: TextView
    private lateinit var obstacleAlertTextView: TextView
    private lateinit var obstacleSeverityBadge: TextView
    private var lastObstacleDisplayTime = 0L

    // Demo / Examiner Mode UI
    private var isDemoMode = false
    private lateinit var demoModeButton: Button
    private lateinit var demoDiagnosticCard: LinearLayout
    private lateinit var demoDiagnosticTextView: TextView

    // Search UI (IDLE State)
    private lateinit var searchContainer: LinearLayout
    private lateinit var searchEditText: EditText
    private lateinit var searchSubmitButton: Button
    private lateinit var searchVoiceButton: Button

    // Destination Preview Card (DESTINATION_SELECTED State)
    private lateinit var destinationPreviewCard: LinearLayout
    private lateinit var destinationTitleTextView: TextView
    private lateinit var destinationDetailsTextView: TextView
    private lateinit var startNavButton: Button
    private lateinit var cancelPreviewButton: Button
    private var currentPreview: RoutePreview? = null

    // Turn-by-turn Navigation HUD (NAVIGATING State)
    private lateinit var navHudLayout: LinearLayout
    private lateinit var navArrowTextView: TextView
    private lateinit var navInstructionTextView: TextView
    private lateinit var navNextStepTextView: TextView
    private lateinit var navDistanceTextView: TextView
    private lateinit var stopNavButton: Button

    // Destination Reached Card (ARRIVED State)
    private lateinit var arrivedCard: LinearLayout
    private lateinit var arrivedTitleTextView: TextView
    private lateinit var arrivedDetailsTextView: TextView
    private lateinit var arrivedDoneButton: Button

    // Location, Mode & Emergency Controls
    private lateinit var locationTextView: TextView
    private lateinit var controlRow: LinearLayout
    private lateinit var viewModeButton: Button
    private lateinit var emergencyButton: Button
    private lateinit var mapHintTextView: TextView

    // Managers & Engines
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
    private lateinit var connectionGpsRow: NavigationComponents.ConnectionGpsViewHolder
    private lateinit var accessibleVoiceButton: Button
    private lateinit var offlineAreasButton: Button
    private val navigationManager = InAppNavigationManager()

    // Navigation Map Elements
    private var destinationMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var selectedPinLatLng: LatLng? = null
    private var currentViewMode = ViewMode.SPLIT
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                if (::connectionGpsRow.isInitialized) {
                    connectionGpsRow.updateNetwork(status)
                }
                if (announcement != null) {
                    feedbackEngine.speakNormal(announcement)
                }
            }
        }
        onlineOfflineManager.startMonitoring()

        locationHelper.onGpsConfidenceChanged = { confidence, announcement ->
            runOnUiThread {
                if (::connectionGpsRow.isInitialized) {
                    connectionGpsRow.updateGps(confidence)
                }
                if (announcement != null) {
                    feedbackEngine.speakNormal(announcement)
                }
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
                    runOnUiThread {
                        showEmergencySetupDialog()
                    }
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
                val msg = if (isDetectionPaused) "Detection Paused." else "Detection Resumed."
                runOnUiThread {
                    statusTextView.text = msg
                }
            },
            onSpeechRateChanged = { newRate ->
                feedbackEngine.setSpeechRate(newRate)
            },
            onToggleTorch = { enable ->
                if (::cameraXManager.isInitialized) {
                    cameraXManager.enableTorch(enable)
                }
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
            onEmergencyContactUpdated = { updatedNumber ->
                emergencyContactPhone = updatedNumber
            },
            onToggleMapView = {
                runOnUiThread { cycleViewMode() }
            },
            onRepeatNavigationDirection = {
                announceCurrentDirection()
            },
            onCancelNavigation = {
                runOnUiThread { stopWalkingNavigation() }
            },
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
            onListeningStarted = {
                if (::environmentalAudioEngine.isInitialized) {
                    environmentalAudioEngine.pauseListening()
                }
            },
            onListeningStopped = {
                if (::environmentalAudioEngine.isInitialized) {
                    environmentalAudioEngine.resumeListening()
                }
            },
            onTriggerEmergencySos = {
                runOnUiThread {
                    showEmergencySosDialog()
                }
            },
            onOpenEmergencySetup = {
                runOnUiThread {
                    showEmergencySetupDialog()
                }
            },
            onPauseNavigation = {
                runOnUiThread {
                    navigationManager.pauseNavigation()
                    statusTextView.text = "⏸ NAVIGATION PAUSED"
                    statusTextView.setTextColor(Color.parseColor("#FFD600"))
                }
            },
            onResumeNavigation = {
                runOnUiThread {
                    navigationManager.resumeNavigation()
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
            onStopSafety = {
                runOnUiThread {
                    if (navigationManager.isNavigating) {
                        stopWalkingNavigation()
                    }
                }
            },
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
            onShowOfflineAreas = {
                runOnUiThread {
                    showOfflineAreasDialog()
                }
            },
            onDeleteOfflineArea = { areaName ->
                runOnUiThread {
                    offlineAreaManager.deleteAreaByName(areaName) { deleted ->
                        if (deleted) {
                            feedbackEngine.speakNormal("Deleted offline area $areaName.")
                        } else {
                            feedbackEngine.speakNormal("Offline area $areaName not found.")
                        }
                    }
                }
            }
        )

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
            runOnUiThread {
                voiceAssistant.startListening()
            }
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

    private fun setupAccessibleUiLayout(savedInstanceState: Bundle?) {
        rootLayout = FrameLayout(this).apply {
            contentDescription = "Blind Navigation Main Screen. View split camera and live map. Double tap to pause detection. Long press to speak."
        }

        // Split view container: Camera on top, Map on bottom
        mainContentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Camera View Container
        cameraContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cameraContainer.addView(previewView)

        overlayView = BoundingBoxOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        cameraContainer.addView(overlayView)

        mainContentLayout.addView(cameraContainer)

        // 2. Map View Container
        mapContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        mapView = MapView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            googleMap = map
            setupGoogleMap(map)
        }
        mapContainer.addView(mapView)

        // Hint overlay on Map
        mapHintTextView = TextView(this).apply {
            text = "📍 Tap or search above to set your walking destination"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(20, 10, 20, 10)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
            }
        }
        mapContainer.addView(mapHintTextView)

        mainContentLayout.addView(mapContainer)
        rootLayout.addView(mainContentLayout)

        // 3. Top Floating Overlays Container (HUD, Status, Search, Preview, Obstacle Alerts, Location)
        val topOverlayContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 36, 24, 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 3a0. Connection and GPS Status Badges
        connectionGpsRow = NavigationComponents.createConnectionGpsRow(this)
        topOverlayContainer.addView(connectionGpsRow.container)
        connectionGpsRow.updateNetwork(onlineOfflineManager.currentStatus)
        connectionGpsRow.updateGps(locationHelper.currentGpsConfidence)

        // 3a. AI Status Pill
        statusTextView = NavigationComponents.createStatusPill(this).apply {
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, 10)
            }
            layoutParams = p
        }
        topOverlayContainer.addView(statusTextView)

        // 3a2. Contextual System Banner (GPS Weak, Offline, Camera Unavailable)
        systemBanner = NavigationComponents.createSystemBanner(this)
        topOverlayContainer.addView(systemBanner)

        // 3b. Dedicated Debounced Obstacle Warning Banner
        obstacleAlertCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_obstacle_alert)
            setPadding(24, 16, 24, 16)
            elevation = 16f
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            contentDescription = "Active obstacle alert banner."
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 10)
            }
            layoutParams = p
        }

        obstacleAlertIcon = TextView(this).apply {
            text = "⚠️"
            textSize = 24f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 16, 0)
            }
        }
        obstacleAlertCard.addView(obstacleAlertIcon)

        obstacleAlertTextView = TextView(this).apply {
            text = "Obstacle detected"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        obstacleAlertCard.addView(obstacleAlertTextView)

        obstacleSeverityBadge = TextView(this).apply {
            text = "CAUTION"
            textSize = 12f
            setTextColor(Color.parseColor("#FFD600"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_status_pill)
            setPadding(18, 8, 18, 8)
        }
        obstacleAlertCard.addView(obstacleSeverityBadge)

        topOverlayContainer.addView(obstacleAlertCard)

        // 3c. Main Actionable Path Guidance Card (Action-first prompt)
        mainGuidanceCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_glass_card)
            setPadding(28, 14, 28, 14)
            elevation = 12f
            gravity = Gravity.CENTER
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 10)
            }
            layoutParams = p
        }

        mainGuidanceTextView = TextView(this).apply {
            text = "Path clear. Continue straight."
            textSize = 16f
            setTextColor(Color.parseColor("#00E676"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            contentDescription = "Current path guidance: Path clear. Continue straight."
        }
        mainGuidanceCard.addView(mainGuidanceTextView)
        topOverlayContainer.addView(mainGuidanceCard)

        // 3d. Accessible Destination Search Bar (IDLE State)
        searchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_search_bar)
            setPadding(20, 10, 14, 10)
            elevation = 12f
            gravity = Gravity.CENTER_VERTICAL
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 10)
            }
            layoutParams = p
        }

        searchEditText = EditText(this).apply {
            hint = "Where are you going?"
            setHintTextColor(Color.parseColor("#90A4AE"))
            setTextColor(Color.WHITE)
            textSize = 15f
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            contentDescription = "Destination search input. Enter an address or landmark."
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    executeDestinationSearch(text.toString())
                    true
                } else false
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && currentUiState == NavigationUiState.HOME) {
                    updateUiForState(NavigationUiState.DESTINATION_SEARCH)
                }
            }
        }
        searchContainer.addView(searchEditText)

        searchSubmitButton = Button(this).apply {
            text = "🔍"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_primary_button)
            contentDescription = "Search for entered destination address or place name."
            layoutParams = LinearLayout.LayoutParams(100, 100).apply {
                setMargins(6, 0, 4, 0)
            }
            setOnClickListener {
                executeDestinationSearch(searchEditText.text.toString())
            }
        }
        searchContainer.addView(searchSubmitButton)

        searchVoiceButton = Button(this).apply {
            text = "🎤"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_primary_button)
            contentDescription = "Voice search. Tap and speak your destination."
            layoutParams = LinearLayout.LayoutParams(100, 100).apply {
                setMargins(4, 0, 0, 0)
            }
            setOnClickListener {
                voiceAssistant.startListening()
            }
        }
        searchContainer.addView(searchVoiceButton)

        topOverlayContainer.addView(searchContainer)

        // 3d. Destination Selected Preview Card (DESTINATION_SELECTED State)
        destinationPreviewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_nav_hud)
            setPadding(24, 20, 24, 20)
            elevation = 16f
            visibility = View.GONE
            contentDescription = "Selected destination preview card."
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 10)
            }
            layoutParams = p
        }

        val previewHeaderTextView = TextView(this).apply {
            text = "🎯 DESTINATION SELECTED"
            textSize = 12f
            setTextColor(Color.parseColor("#00E5FF"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        destinationPreviewCard.addView(previewHeaderTextView)

        destinationTitleTextView = TextView(this).apply {
            text = "Selected Destination"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 4, 0, 2)
        }
        destinationPreviewCard.addView(destinationTitleTextView)

        destinationDetailsTextView = TextView(this).apply {
            text = "Calculating walking route..."
            textSize = 14f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, 0, 0, 14)
        }
        destinationPreviewCard.addView(destinationDetailsTextView)

        val previewButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        startNavButton = Button(this).apply {
            text = "▶ START NAVIGATION"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_secondary_button)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            elevation = 8f
            contentDescription = "Start walking navigation to selected destination."
            layoutParams = LinearLayout.LayoutParams(0, 130, 1.0f).apply {
                setMargins(0, 0, 8, 0)
            }
            setOnClickListener {
                startWalkingNavigationFromPreview()
            }
        }
        previewButtonsRow.addView(startNavButton)

        cancelPreviewButton = Button(this).apply {
            text = "✕ CANCEL"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_primary_button)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            elevation = 6f
            contentDescription = "Cancel destination selection and return to search."
            layoutParams = LinearLayout.LayoutParams(220, 130)
            setOnClickListener {
                cancelDestinationSelection()
            }
        }
        previewButtonsRow.addView(cancelPreviewButton)

        destinationPreviewCard.addView(previewButtonsRow)
        topOverlayContainer.addView(destinationPreviewCard)

        // 3e. Turn-by-Turn Navigation HUD Card (NAVIGATING State)
        navHudLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_nav_hud)
            setPadding(24, 18, 24, 18)
            elevation = 18f
            visibility = View.GONE
            contentDescription = "Turn by turn walking instruction. Tap to hear directions aloud."
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 10)
            }
            layoutParams = p
            setOnClickListener {
                announceCurrentDirection()
            }
        }

        val navManeuverRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        navArrowTextView = TextView(this).apply {
            text = "⬆️"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_maneuver_badge)
            layoutParams = LinearLayout.LayoutParams(120, 120)
        }
        navManeuverRow.addView(navArrowTextView)

        val navTextContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 0, 8, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        navInstructionTextView = TextView(this).apply {
            text = "Walk straight"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        navTextContainer.addView(navInstructionTextView)

        navNextStepTextView = TextView(this).apply {
            text = "Next: Follow path to destination"
            textSize = 14f
            setTextColor(Color.parseColor("#80D8FF"))
            setPadding(0, 2, 0, 2)
        }
        navTextContainer.addView(navNextStepTextView)

        navDistanceTextView = TextView(this).apply {
            text = "Calculating..."
            textSize = 13f
            setTextColor(Color.parseColor("#CFD8DC"))
        }
        navTextContainer.addView(navDistanceTextView)

        navManeuverRow.addView(navTextContainer)
        navHudLayout.addView(navManeuverRow)

        // Obvious, Accessible STOP NAVIGATION Button
        stopNavButton = Button(this).apply {
            text = "⏹ STOP NAVIGATION"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_emergency_sos)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            elevation = 10f
            contentDescription = "Stop walking navigation."
            val sp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                115
            ).apply {
                setMargins(0, 14, 0, 0)
            }
            layoutParams = sp
            setOnClickListener {
                stopWalkingNavigation()
            }
        }
        navHudLayout.addView(stopNavButton)

        topOverlayContainer.addView(navHudLayout)

        // 3f. Destination Arrived Card (ARRIVED State)
        arrivedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_nav_hud)
            setPadding(24, 20, 24, 20)
            elevation = 18f
            visibility = View.GONE
            contentDescription = "Destination reached banner."
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 10)
            }
            layoutParams = p
        }

        arrivedTitleTextView = TextView(this).apply {
            text = "🎯 ARRIVED AT DESTINATION!"
            textSize = 18f
            setTextColor(Color.parseColor("#00E676"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        arrivedCard.addView(arrivedTitleTextView)

        arrivedDetailsTextView = TextView(this).apply {
            text = "You have successfully reached your destination."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 6, 0, 14)
        }
        arrivedCard.addView(arrivedDetailsTextView)

        arrivedDoneButton = Button(this).apply {
            text = "✔ FINISH NAVIGATION"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_secondary_button)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            elevation = 8f
            contentDescription = "Finish navigation and return to idle screen."
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                125
            )
            setOnClickListener {
                cancelDestinationSelection()
            }
        }
        arrivedCard.addView(arrivedDoneButton)

        topOverlayContainer.addView(arrivedCard)

        // 3g. Live Location Banner
        locationTextView = TextView(this).apply {
            text = "📍 GPS Locating..."
            textSize = 14f
            setTextColor(Color.parseColor("#00E5FF"))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_glass_card)
            setPadding(28, 14, 28, 14)
            elevation = 8f
            contentDescription = "Current location. Tap to announce aloud."
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 10)
            }
            layoutParams = p
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

        // 3h. View Mode & Demo Mode Control Row
        controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 10)
            }
            layoutParams = p
        }

        viewModeButton = Button(this).apply {
            text = "📷/🗺 VIEW"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_primary_button)
            elevation = 8f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "Toggle display mode between Split View, Camera Only, or Map Only."
            layoutParams = LinearLayout.LayoutParams(
                0,
                120,
                1.0f
            ).apply {
                setMargins(0, 0, 4, 0)
            }
            setOnClickListener {
                cycleViewMode()
            }
        }
        controlRow.addView(viewModeButton)

        demoModeButton = Button(this).apply {
            text = "🛠️ DEMO"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_glass_card)
            elevation = 8f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "Toggle Developer and Examiner Demo mode with live AI diagnostics and bounding boxes."
            layoutParams = LinearLayout.LayoutParams(
                0,
                120,
                1.0f
            ).apply {
                setMargins(4, 0, 4, 0)
            }
            setOnClickListener {
                isDemoMode = !isDemoMode
                overlayView.isDemoModeEnabled = isDemoMode
                text = if (isDemoMode) "🛠️ DEMO ON" else "🛠️ DEMO"
                setTextColor(if (isDemoMode) Color.parseColor("#00E5FF") else Color.WHITE)
                demoDiagnosticCard.visibility = if (isDemoMode) View.VISIBLE else View.GONE
                feedbackEngine.speakNormal(
                    if (isDemoMode) "Demo mode enabled. AI bounding boxes and multi-sensor diagnostics active."
                    else "Demo mode disabled. Calm assistive path guidance active."
                )
            }
        }
        controlRow.addView(demoModeButton)

        offlineAreasButton = Button(this).apply {
            text = "🗺️ AREAS"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_primary_button)
            elevation = 8f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "Offline Areas. Download or manage offline maps and routes."
            layoutParams = LinearLayout.LayoutParams(
                0,
                120,
                1.0f
            ).apply {
                setMargins(4, 0, 4, 0)
            }
            setOnClickListener {
                showOfflineAreasDialog()
            }
        }
        controlRow.addView(offlineAreasButton)

        val guardianButton = Button(this).apply {
            text = "🛡️ GUARD"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_secondary_button)
            elevation = 8f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "Emergency Contact Settings. Configure primary Guardian and backup contact."
            layoutParams = LinearLayout.LayoutParams(
                0,
                120,
                1.0f
            ).apply {
                setMargins(4, 0, 0, 0)
            }
            setOnClickListener {
                showEmergencySetupDialog()
            }
        }
        controlRow.addView(guardianButton)
        topOverlayContainer.addView(controlRow)

        // 3i. Multi-Sensor Examiner Diagnostics Card (Visible only in Demo Mode)
        demoDiagnosticCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_glass_card)
            setPadding(20, 14, 20, 14)
            elevation = 14f
            visibility = View.GONE
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 10)
            }
            layoutParams = p
        }

        demoDiagnosticTextView = TextView(this).apply {
            text = "🤖 AI DEMO: Vision: Idle | Audio: Active | Path: Clear"
            textSize = 12f
            setTextColor(Color.parseColor("#00E5FF"))
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(0, 0, 0, 8)
        }
        demoDiagnosticCard.addView(demoDiagnosticTextView)

        val testRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun createAcousticTestBtn(label: String, type: AudioEventType): Button {
            return Button(this).apply {
                text = label
                textSize = 11f
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_primary_button)
                layoutParams = LinearLayout.LayoutParams(0, 90, 1.0f).apply {
                    setMargins(3, 0, 3, 0)
                }
                setOnClickListener {
                    environmentalAudioEngine.simulateAudioEvent(type, 0.92f)
                    Toast.makeText(this@MainActivity, "Simulated acoustic: ${type.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        testRow.addView(createAcousticTestBtn("🔊 Horn", AudioEventType.VEHICLE_HORN))
        testRow.addView(createAcousticTestBtn("🚨 Siren", AudioEventType.SIREN))
        testRow.addView(createAcousticTestBtn("🔔 Bell", AudioEventType.BICYCLE_BELL))
        testRow.addView(createAcousticTestBtn("🚗 Engine", AudioEventType.ENGINE_RUMBLE))
        demoDiagnosticCard.addView(testRow)

        topOverlayContainer.addView(demoDiagnosticCard)

        rootLayout.addView(topOverlayContainer)

        // 4. Bottom Accessible Button Row (Voice Command + Emergency SOS)
        val bottomActionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            elevation = 20f
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                setMargins(20, 0, 20, 30)
            }
            layoutParams = params
        }

        accessibleVoiceButton = NavigationComponents.createVoiceButton(this) {
            voiceAssistant.startListening()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, 160, 1.2f).apply {
                setMargins(0, 0, 8, 0)
            }
        }
        bottomActionContainer.addView(accessibleVoiceButton)

        emergencyButton = NavigationComponents.createEmergencyButton(this) {
            showEmergencySosDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, 160, 1.0f).apply {
                setMargins(8, 0, 0, 0)
            }
        }
        bottomActionContainer.addView(emergencyButton)

        rootLayout.addView(bottomActionContainer)

        setContentView(rootLayout)
        updateUiForState(NavigationUiState.HOME)
    }

    private fun updateUiForState(state: NavigationUiState) {
        currentUiState = state
        currentNavState = when (state) {
            NavigationUiState.HOME, NavigationUiState.DESTINATION_SEARCH, NavigationUiState.SETTINGS -> NavState.IDLE
            NavigationUiState.ROUTE_PREVIEW -> NavState.DESTINATION_SELECTED
            NavigationUiState.NAV_PATH_CLEAR, NavigationUiState.NAV_APPROACHING_TURN,
            NavigationUiState.OBSTACLE_WARNING, NavigationUiState.CRITICAL_STOP,
            NavigationUiState.PATH_UNCLEAR, NavigationUiState.GPS_WEAK,
            NavigationUiState.OFFLINE, NavigationUiState.CAMERA_UNAVAILABLE -> NavState.NAVIGATING
            NavigationUiState.DESTINATION_REACHED -> NavState.ARRIVED
            NavigationUiState.GUARDIAN_EMERGENCY -> if (navigationManager.isNavigating) NavState.NAVIGATING else NavState.IDLE
        }

        runOnUiThread {
            // Dual-encoded status indicator (Icon + Text)
            val (statusText, statusColor) = when (state) {
                NavigationUiState.CRITICAL_STOP -> Pair("🛑 STOP IMMEDIATELY", Color.parseColor("#FF1744"))
                NavigationUiState.OBSTACLE_WARNING -> Pair("⚠️ CAUTION: OBSTACLE", Color.parseColor("#FFD600"))
                NavigationUiState.PATH_UNCLEAR -> Pair("❓ PATH UNCLEAR | PLEASE STOP", Color.parseColor("#FF9100"))
                NavigationUiState.NAV_APPROACHING_TURN -> Pair("↗️ APPROACHING TURN", Color.parseColor("#00E5FF"))
                NavigationUiState.NAV_PATH_CLEAR -> Pair("● PATH CLEAR | NAVIGATING", Color.parseColor("#00E676"))
                NavigationUiState.DESTINATION_REACHED -> Pair("🎯 ARRIVED AT DESTINATION", Color.parseColor("#00E676"))
                NavigationUiState.ROUTE_PREVIEW -> Pair("📍 ROUTE PREVIEW", Color.parseColor("#00E5FF"))
                NavigationUiState.DESTINATION_SEARCH -> Pair("🔍 SEARCHING DESTINATION", Color.parseColor("#00E5FF"))
                NavigationUiState.GUARDIAN_EMERGENCY -> Pair("🚨 EMERGENCY SOS", Color.parseColor("#FF1744"))
                NavigationUiState.SETTINGS -> Pair("⚙️ GUARDIAN SETTINGS", Color.parseColor("#00E5FF"))
                NavigationUiState.GPS_WEAK -> Pair("📡 GPS SIGNAL WEAK", Color.parseColor("#FFD600"))
                NavigationUiState.OFFLINE -> Pair("📶 OFFLINE MODE", Color.parseColor("#FFD600"))
                NavigationUiState.CAMERA_UNAVAILABLE -> Pair("📷 CAMERA UNAVAILABLE", Color.parseColor("#FF1744"))
                NavigationUiState.HOME -> Pair(
                    if (isDetectionPaused) "⏸ DETECTION PAUSED" else "● PATH CLEAR | AI SCANNING",
                    if (isDetectionPaused) Color.parseColor("#FFD600") else Color.parseColor("#00E676")
                )
            }
            statusTextView.text = statusText
            statusTextView.setTextColor(statusColor)

            // System banner handling for hardware/system states
            when (state) {
                NavigationUiState.GPS_WEAK -> {
                    systemBanner.visibility = View.VISIBLE
                    systemBanner.text = "📡 GPS Signal Weak. Position estimated by motion."
                }
                NavigationUiState.OFFLINE -> {
                    systemBanner.visibility = View.VISIBLE
                    systemBanner.text = "📶 Offline Mode Active. On-device vision & safety active."
                }
                NavigationUiState.CAMERA_UNAVAILABLE -> {
                    systemBanner.visibility = View.VISIBLE
                    systemBanner.text = "📷 Camera Feed Unavailable. Please check lens."
                }
                else -> {
                    if (currentUiState != NavigationUiState.GPS_WEAK &&
                        currentUiState != NavigationUiState.OFFLINE &&
                        currentUiState != NavigationUiState.CAMERA_UNAVAILABLE) {
                        systemBanner.visibility = View.GONE
                    }
                }
            }

            // Visible Cards
            when (state) {
                NavigationUiState.HOME, NavigationUiState.DESTINATION_SEARCH -> {
                    searchContainer.visibility = View.VISIBLE
                    destinationPreviewCard.visibility = View.GONE
                    navHudLayout.visibility = View.GONE
                    arrivedCard.visibility = View.GONE
                    controlRow.visibility = View.VISIBLE
                    mainGuidanceCard.visibility = View.VISIBLE
                    mapHintTextView.text = "📍 Tap or search above to choose your destination"
                }
                NavigationUiState.ROUTE_PREVIEW -> {
                    searchContainer.visibility = View.GONE
                    destinationPreviewCard.visibility = View.VISIBLE
                    navHudLayout.visibility = View.GONE
                    arrivedCard.visibility = View.GONE
                    controlRow.visibility = View.GONE
                    mainGuidanceCard.visibility = View.GONE
                    mapHintTextView.text = "📍 Route preview. Tap START NAVIGATION below."
                }
                NavigationUiState.NAV_PATH_CLEAR, NavigationUiState.NAV_APPROACHING_TURN,
                NavigationUiState.OBSTACLE_WARNING, NavigationUiState.CRITICAL_STOP,
                NavigationUiState.PATH_UNCLEAR -> {
                    searchContainer.visibility = View.GONE
                    destinationPreviewCard.visibility = View.GONE
                    navHudLayout.visibility = View.VISIBLE
                    arrivedCard.visibility = View.GONE
                    controlRow.visibility = View.GONE
                    mainGuidanceCard.visibility = View.GONE
                    mapHintTextView.text = "📍 Navigating. Follow turn-by-turn guidance."
                }
                NavigationUiState.DESTINATION_REACHED -> {
                    searchContainer.visibility = View.GONE
                    destinationPreviewCard.visibility = View.GONE
                    navHudLayout.visibility = View.GONE
                    arrivedCard.visibility = View.VISIBLE
                    controlRow.visibility = View.GONE
                    mainGuidanceCard.visibility = View.GONE
                    mapHintTextView.text = "🎯 Destination reached!"
                }
                NavigationUiState.GUARDIAN_EMERGENCY, NavigationUiState.SETTINGS -> {
                    // Modal dialogs handled separately
                }
                else -> {}
            }
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

    private fun setupGoogleMap(map: GoogleMap) {
        try {
            map.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_dark))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            if (allPermissionsGranted()) {
                map.isMyLocationEnabled = true
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = true

        locationHelper.lastLocation?.let { loc ->
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 17.5f))
        }

        // Tap or Long-press on map to select walking destination
        map.setOnMapClickListener { latLng ->
            if (currentNavState != NavState.NAVIGATING) {
                handleMapPointSelected(latLng)
            }
        }
        map.setOnMapLongClickListener { latLng ->
            if (currentNavState != NavState.NAVIGATING) {
                handleMapPointSelected(latLng)
            }
        }
    }

    private fun handleMapPointSelected(latLng: LatLng) {
        selectedPinLatLng = latLng
        destinationMarker?.remove()
        val map = googleMap ?: return

        destinationMarker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .title("Selected Destination")
                .snippet("Calculating walking route...")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
        destinationMarker?.showInfoWindow()

        val currentLoc = locationHelper.lastLocation ?: Location("user").apply {
            latitude = latLng.latitude - 0.0020
            longitude = latLng.longitude - 0.0020
        }

        val targetLoc = Location("pin").apply {
            latitude = latLng.latitude
            longitude = latLng.longitude
        }

        // Reverse geocode in background to obtain real street/place name
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

                updateUiForState(NavigationUiState.ROUTE_PREVIEW)
                feedbackEngine.speakNormal("Destination selected: $placeName. Distance: $distStr. Estimated ${preview.estimatedMinutes} minutes walk. Say START to begin walking navigation, or say CANCEL.")
                lifecycleScope.launch(Dispatchers.Main) {
                    delay(4500L)
                    if (currentUiState == NavigationUiState.ROUTE_PREVIEW) {
                        voiceAssistant.startListening()
                    }
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
                        val destLoc = Location("cache").apply {
                            latitude = match.destinationLat
                            longitude = match.destinationLng
                        }
                        val currentLoc = locationHelper.lastLocation ?: Location("user").apply {
                            latitude = match.destinationLat - 0.0020
                            longitude = match.destinationLng - 0.0020
                        }
                        val progress = navigationManager.startNavigationFromCache(
                            cachedDestination = match.destinationName,
                            targetLocation = destLoc,
                            routeJson = match.routeJson,
                            currentLocation = currentLoc
                        )
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
                    selectedPinLatLng = LatLng(targetLoc.latitude, targetLoc.longitude)

                    val currentLoc = locationHelper.lastLocation ?: Location("user").apply {
                        latitude = targetLoc.latitude - 0.0025
                        longitude = targetLoc.longitude - 0.0020
                    }

                    val preview = navigationManager.previewRoute(currentLoc, targetLoc, displayName)
                    currentPreview = preview

                    destinationMarker?.remove()
                    destinationMarker = googleMap?.addMarker(
                        MarkerOptions()
                            .position(LatLng(targetLoc.latitude, targetLoc.longitude))
                            .title(displayName)
                            .snippet("${navigationManager.formatDistance(preview.totalDistanceMeters)} (${preview.estimatedMinutes} min walk)")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    )
                    destinationMarker?.showInfoWindow()

                    drawRoutePreviewOnMap(currentLoc, preview)

                    destinationTitleTextView.text = displayName
                    val distStr = navigationManager.formatDistance(preview.totalDistanceMeters)
                    destinationDetailsTextView.text = "Distance: $distStr  •  ~${preview.estimatedMinutes} mins walk"

                    updateUiForState(NavigationUiState.ROUTE_PREVIEW)
                    feedbackEngine.speakNormal("Found $displayName. Distance: $distStr. Estimated ${preview.estimatedMinutes} minutes walk. Say START to begin walking navigation, or say CANCEL.")
                    lifecycleScope.launch(Dispatchers.Main) {
                        delay(4500L)
                        if (currentUiState == NavigationUiState.ROUTE_PREVIEW) {
                            voiceAssistant.startListening()
                        }
                    }
                } else {
                    feedbackEngine.speakNormal("Could not find location for $trimmed. Please check spelling or try a nearby street name.")
                    Toast.makeText(this@MainActivity, "Destination not found. Try another search.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun drawRoutePreviewOnMap(start: Location, preview: RoutePreview) {
        val map = googleMap ?: return
        routePolyline?.remove()

        val points = mutableListOf<LatLng>()
        points.add(LatLng(start.latitude, start.longitude))
        preview.routeSteps.forEach { step ->
            points.add(LatLng(step.targetLocation.latitude, step.targetLocation.longitude))
        }

        routePolyline = map.addPolyline(
            PolylineOptions()
                .addAll(points)
                .width(16f)
                .color(Color.parseColor("#00E5FF"))
                .geodesic(true)
        )

        try {
            val boundsBuilder = LatLngBounds.Builder()
            points.forEach { boundsBuilder.include(it) }
            val bounds = boundsBuilder.build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
        } catch (e: Exception) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(start.latitude, start.longitude), 17.5f))
        }
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
                val targetLoc = Location("pin").apply {
                    latitude = pin.latitude
                    longitude = pin.longitude
                }
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

        // Cache route to Room DB for offline fallback
        lifecycleScope.launch(Dispatchers.IO) {
            val stepsJson = navigationManager.serializeRouteToJson(preview.routeSteps)
            val cachedRoute = CachedRouteEntity(
                destinationName = preview.destinationName,
                destinationLat = preview.destinationLocation.latitude,
                destinationLng = preview.destinationLocation.longitude,
                routeJson = stepsJson,
                totalDistanceMeters = preview.totalDistanceMeters
            )
            database.detectionDao().saveCachedRoute(cachedRoute)
        }

        val firstStep = progress.nextStep?.instruction ?: "Walk straight"
        val dist = progress.distanceToNextStepMeters.toInt()
        val speech = "Starting walking navigation to ${preview.destinationName}. In $dist meters, $firstStep. You can say: Next turn, How far, or Stop navigation at any time."
        feedbackEngine.speakNormal(speech)

        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(currentLoc.latitude, currentLoc.longitude), 18.5f))
    }

    private fun startWalkingNavigation(targetLocation: Location, targetName: String) {
        val currentLoc = locationHelper.lastLocation ?: Location("user").apply {
            latitude = targetLocation.latitude - 0.0020
            longitude = targetLocation.longitude - 0.0020
        }

        val progress = navigationManager.startNavigation(currentLoc, targetLocation, targetName)
        drawRouteOnMap(currentLoc, targetLocation)
        updateNavHud(progress)
        updateUiForState(NavState.NAVIGATING)
        onlineOfflineManager.isNavigatingActive = true

        // Cache route to Room DB for offline fallback
        lifecycleScope.launch(Dispatchers.IO) {
            val stepsJson = navigationManager.serializeRouteToJson(navigationManager.currentSteps)
            val cachedRoute = CachedRouteEntity(
                destinationName = targetName,
                destinationLat = targetLocation.latitude,
                destinationLng = targetLocation.longitude,
                routeJson = stepsJson,
                totalDistanceMeters = progress.totalRemainingDistanceMeters
            )
            database.detectionDao().saveCachedRoute(cachedRoute)
        }

        val firstStep = progress.nextStep?.instruction ?: "Walk straight"
        val dist = progress.distanceToNextStepMeters.toInt()
        val speech = "Starting walking navigation to $targetName. In $dist meters, $firstStep. You can say: Next turn, How far, or Stop navigation at any time."
        feedbackEngine.speakNormal(speech)

        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(currentLoc.latitude, currentLoc.longitude), 18.5f))
    }

    private fun stopWalkingNavigation() {
        navigationManager.stopNavigation()
        onlineOfflineManager.isNavigatingActive = false
        routePolyline?.remove()
        routePolyline = null
        destinationMarker?.remove()
        destinationMarker = null
        selectedPinLatLng = null
        currentPreview = null
        updateUiForState(NavState.IDLE)
        feedbackEngine.speakNormal("Walking navigation stopped.")
    }

    private fun cancelDestinationSelection() {
        routePolyline?.remove()
        routePolyline = null
        destinationMarker?.remove()
        destinationMarker = null
        selectedPinLatLng = null
        currentPreview = null
        updateUiForState(NavState.IDLE)
        feedbackEngine.speakNormal("Destination selection canceled.")
    }

    private fun showOfflineAreasDialog() {
        val dialog = OfflineAreasDialog(
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
            onDownloadNewArea = {
                feedbackEngine.speakNormal("Please say: Download area, followed by the city name.")
            }
        )
        dialog.show()
    }

    private fun showEmergencySosDialog() {
        val prevState = currentUiState
        updateUiForState(NavigationUiState.GUARDIAN_EMERGENCY)
        if (emergencyRepository.hasGuardian()) {
            val dialog = EmergencySosDialog(
                context = this,
                repository = emergencyRepository,
                emergencySosHandler = emergencySosHandler,
                feedbackEngine = feedbackEngine,
                onOpenSettings = {
                    showEmergencySetupDialog()
                },
                onDismiss = {
                    updateUiForState(prevState)
                }
            )
            dialog.show()
        } else {
            feedbackEngine.speakUrgent("No emergency contact configured! Please configure your Guardian contact.")
            showEmergencySetupDialog()
        }
    }

    private fun showEmergencySetupDialog() {
        val prevState = currentUiState
        updateUiForState(NavigationUiState.SETTINGS)
        val setupDialog = EmergencyContactSetupDialog(
            context = this,
            repository = emergencyRepository,
            feedbackEngine = feedbackEngine,
            onSaved = { config ->
                emergencyContactPhone = config.primaryGuardian?.phoneNumber.orEmpty()
            },
            onDismiss = {
                updateUiForState(prevState)
            }
        )
        setupDialog.show()
    }

    private fun cycleViewMode() {
        currentViewMode = when (currentViewMode) {
            ViewMode.SPLIT -> ViewMode.CAMERA
            ViewMode.CAMERA -> ViewMode.MAP
            ViewMode.MAP -> ViewMode.SPLIT
        }
        applyViewMode(currentViewMode)
    }

    private fun applyViewMode(mode: ViewMode) {
        when (mode) {
            ViewMode.SPLIT -> {
                cameraContainer.visibility = View.VISIBLE
                (cameraContainer.layoutParams as LinearLayout.LayoutParams).weight = 1.0f
                mapContainer.visibility = View.VISIBLE
                (mapContainer.layoutParams as LinearLayout.LayoutParams).weight = 1.0f
                viewModeButton.text = "📷/🗺 SPLIT VIEW"
                feedbackEngine.speakNormal("Split screen mode active. Showing camera and live map.")
            }
            ViewMode.CAMERA -> {
                cameraContainer.visibility = View.VISIBLE
                (cameraContainer.layoutParams as LinearLayout.LayoutParams).weight = 1.0f
                mapContainer.visibility = View.GONE
                viewModeButton.text = "📷 CAMERA ONLY"
                feedbackEngine.speakNormal("Camera mode active.")
            }
            ViewMode.MAP -> {
                cameraContainer.visibility = View.GONE
                mapContainer.visibility = View.VISIBLE
                (mapContainer.layoutParams as LinearLayout.LayoutParams).weight = 1.0f
                viewModeButton.text = "🗺 MAP ONLY"
                feedbackEngine.speakNormal("Full map mode active.")
            }
        }
        mainContentLayout.requestLayout()
    }

    private fun drawRouteOnMap(start: Location, target: Location) {
        val map = googleMap ?: return
        routePolyline?.remove()

        val points = mutableListOf<LatLng>()
        points.add(LatLng(start.latitude, start.longitude))
        if (navigationManager.currentRoute.isNotEmpty()) {
            navigationManager.currentRoute.forEach { step ->
                points.add(LatLng(step.targetLocation.latitude, step.targetLocation.longitude))
            }
        } else {
            points.add(LatLng(target.latitude, target.longitude))
        }

        routePolyline = map.addPolyline(
            PolylineOptions()
                .addAll(points)
                .width(16f)
                .color(Color.parseColor("#00E5FF"))
                .geodesic(true)
        )

        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(start.latitude, start.longitude), 18.5f))
    }

    private fun updateNavHud(progress: NavProgress) {
        if (!navigationManager.isNavigating) {
            if (progress.isArrived) {
                handleArrival()
            } else {
                navHudLayout.visibility = View.GONE
            }
            return
        }

        if (progress.isArrived) {
            handleArrival()
            return
        }

        navHudLayout.visibility = View.VISIBLE
        navArrowTextView.text = progress.directionSymbol

        val step = progress.nextStep
        if (step != null) {
            val dist = progress.distanceToNextStepMeters.toInt()
            navInstructionTextView.text = "In ${dist}m: ${step.instruction}"
            navNextStepTextView.text = progress.upcomingStep?.let { "Next: ${it.instruction}" } ?: "Follow path to destination"
            val distFormatted = navigationManager.formatDistance(progress.totalRemainingDistanceMeters)
            navDistanceTextView.text = "$distFormatted remaining  •  ~${progress.estimatedRemainingMinutes} mins"
        }
    }

    private fun handleArrival() {
        val destName = navigationManager.destinationName.ifBlank { "your destination" }
        arrivedTitleTextView.text = "🎯 ARRIVED AT DESTINATION!"
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
        currentFocus?.let { view ->
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun initCameraAndServices() {
        // Initialize Environmental Audio Classifier
        environmentalAudioEngine = EnvironmentalAudioEngine(this) { evidence ->
            runOnUiThread {
                if (isDemoMode) {
                    demoDiagnosticTextView.text = "🔊 Acoustic Event: ${evidence.type.name} (${(evidence.confidence * 100).toInt()}%)"
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
                    runOnUiThread {
                        feedbackEngine.speakNormal("Low light detected. Consider saying: Light on, to improve visibility.")
                    }
                }
            },
            onFrameAnalyzed = { bitmap ->
                if (isDetectionPaused) return@CameraXManager

                val detections = objectDetector.detect(bitmap)
                val audioEvidence = environmentalAudioEngine.getRecentAudioEvidence()
                val loc = locationHelper.lastLocation
                val navProgress = if (navigationManager.isNavigating && loc != null) {
                    navigationManager.updateProgress(loc)
                } else null

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
                        // Safe Failure State: Never show "PATH CLEAR" if perception is degraded
                        updateUiForState(NavigationUiState.PATH_UNCLEAR)
                        mainGuidanceTextView.text = decision.instruction
                        mainGuidanceTextView.setTextColor(Color.parseColor("#FF9100"))
                    } else if (decision != null && decision.isObstaclePresent && decision.targetObject != null) {
                        lastObstacleDisplayTime = now
                        val isDanger = decision.priority == SpeechPriority.EMERGENCY
                        val color = if (isDanger) Color.parseColor("#FF1744") else Color.parseColor("#FFD600")

                        obstacleAlertCard.visibility = View.VISIBLE
                        obstacleAlertIcon.text = if (isDanger) "🛑" else "⚠️"
                        obstacleAlertTextView.text = decision.instruction
                        obstacleSeverityBadge.text = if (isDanger) "EMERGENCY" else "CAUTION"
                        obstacleSeverityBadge.setTextColor(color)

                        val nextState = if (isDanger) NavigationUiState.CRITICAL_STOP else NavigationUiState.OBSTACLE_WARNING
                        updateUiForState(nextState)

                        statusTextView.text = decision.visualHeadline
                        statusTextView.setTextColor(color)
                        mainGuidanceTextView.text = decision.instruction
                        mainGuidanceTextView.setTextColor(color)
                    } else {
                        if (now - lastObstacleDisplayTime > 2200L) {
                            obstacleAlertCard.visibility = View.GONE
                            if (navigationManager.isNavigating) {
                                val isNearTurn = (navProgress?.distanceToNextStepMeters ?: 100f) <= 25f
                                updateUiForState(if (isNearTurn) NavigationUiState.NAV_APPROACHING_TURN else NavigationUiState.NAV_PATH_CLEAR)
                            } else {
                                updateUiForState(NavigationUiState.HOME)
                                if (isDetectionPaused) {
                                    statusTextView.text = "⏸ DETECTION PAUSED"
                                    statusTextView.setTextColor(Color.parseColor("#FFD600"))
                                } else {
                                    statusTextView.text = "● PATH CLEAR  |  GUIDANCE ACTIVE"
                                    statusTextView.setTextColor(Color.parseColor("#00E676"))
                                }
                                if (currentNavState == NavState.IDLE) {
                                    mainGuidanceTextView.text = "Path clear. Continue straight."
                                    mainGuidanceTextView.setTextColor(Color.parseColor("#00E676"))
                                }
                            }
                        }
                    }

                    if (isDemoMode) {
                        val corridorCount = detections.count { it.isInCorridor }
                        val audioStr = if (audioEvidence != null) "${audioEvidence.type.name} (${(audioEvidence.confidence * 100).toInt()}%)" else "Quiet (Listening)"
                        val topObjStr = if (detections.isNotEmpty()) "${detections[0].label} ${detections[0].distanceMeters}m" else "None"
                        demoDiagnosticTextView.text = "🤖 AI DEMO: Vision: ${detections.size} objs ($corridorCount in path) | Top: $topObjStr | Audio: $audioStr"
                    }
                }

                logDetectionsToDatabase(detections)
            }
        )

        cameraXManager.startCamera()

        // Start continuous Google Maps-like location tracking
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

                    // Check GPS accuracy: if loc.accuracy > 25m, trigger GPS_WEAK banner
                    if (loc.hasAccuracy() && loc.accuracy > 25.0f) {
                        systemBanner.visibility = View.VISIBLE
                        systemBanner.text = "📡 GPS Signal Weak (±${loc.accuracy.toInt()}m). Estimating motion."
                    } else if (systemBanner.visibility == View.VISIBLE && systemBanner.text.toString().contains("GPS")) {
                        systemBanner.visibility = View.GONE
                    }

                    // When navigating, keep user centered and orient map along user's bearing
                    if (currentNavState == NavState.NAVIGATING) {
                        val cameraUpdate = if (loc.hasBearing() && loc.speed > 0.5f) {
                            val cameraPosition = CameraPosition.Builder()
                                .target(LatLng(loc.latitude, loc.longitude))
                                .zoom(18.5f)
                                .bearing(loc.bearing)
                                .tilt(30f)
                                .build()
                            CameraUpdateFactory.newCameraPosition(cameraPosition)
                        } else {
                            CameraUpdateFactory.newLatLng(LatLng(loc.latitude, loc.longitude))
                        }
                        googleMap?.animateCamera(cameraUpdate)

                        val progress = navigationManager.updateProgress(loc)
                        updateNavHud(progress)

                        val announcement = navigationManager.shouldAnnounceDirection(progress)
                        if (announcement != null) {
                            feedbackEngine.speakNormal(announcement)
                        }
                    } else if (currentNavState == NavState.IDLE) {
                        googleMap?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(loc.latitude, loc.longitude)))
                    }
                }

                if (streetChanged && currentNavState != NavState.NAVIGATING) {
                    locationHelper.lastStreetName?.let { street ->
                        feedbackEngine.speakNormal("Now walking near $street.")
                    }
                }
            }
        )

        feedbackEngine.speakNormal("Blind Navigation System Ready. Camera, Map, and Fall Detection Active.")
    }

    private fun logDetectionsToDatabase(detections: List<com.blindnav.app.ml.DetectedObject>) {
        if (detections.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastDbLogTime < 3000L) return
        lastDbLogTime = now

        lifecycleScope.launch(Dispatchers.IO) {
            val top = detections.first()
            database.detectionDao().insertLog(
                DetectionLog(
                    objectLabel = top.label,
                    confidence = top.confidence,
                    distanceMeters = top.distanceMeters,
                    threatLevel = top.threatLevel.name
                )
            )
        }
    }

    private fun setupGestureListener() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (fallDetector.isCountdownActive) {
                    fallDetector.cancelFallAlert()
                    return true
                }
                return super.onSingleTapConfirmed(e)
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (fallDetector.isCountdownActive) {
                    fallDetector.cancelFallAlert()
                    return true
                }
                isDetectionPaused = !isDetectionPaused
                val msg = if (isDetectionPaused) "Detection Paused." else "Detection Resumed."
                feedbackEngine.speakNormal(msg)
                statusTextView.text = msg
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                feedbackEngine.vibrateCaution()
                voiceAssistant.startListening()
            }
        })

        val touchListener = View.OnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }

        rootLayout.setOnTouchListener(touchListener)
        previewView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            if (allPermissionsGranted()) {
                initCameraAndServices()
                googleMap?.let { setupGoogleMap(it) }
            } else {
                Toast.makeText(this, "Permissions required for Blind Nav App", Toast.LENGTH_LONG).show()
                feedbackEngine.speakUrgent("Camera, Location, and SMS permissions are required for full system functionality.")
            }
        }
    }

    // MapView Lifecycle Management
    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
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
