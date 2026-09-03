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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.MapStyleOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private enum class ViewMode {
        SPLIT, CAMERA, MAP
    }

    private lateinit var mainContentLayout: LinearLayout
    private lateinit var cameraContainer: FrameLayout
    private lateinit var mapContainer: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: BoundingBoxOverlayView
    private lateinit var mapView: MapView
    private var googleMap: GoogleMap? = null

    // UI Elements
    private lateinit var statusTextView: TextView
    private lateinit var locationTextView: TextView
    private lateinit var navHudLayout: LinearLayout
    private lateinit var navArrowTextView: TextView
    private lateinit var navInstructionTextView: TextView
    private lateinit var navDistanceTextView: TextView
    private lateinit var cancelNavButton: Button
    private lateinit var viewModeButton: Button
    private lateinit var navigatePinButton: Button
    private lateinit var emergencyButton: Button

    // Managers & Engines
    private lateinit var cameraXManager: CameraXManager
    private lateinit var objectDetector: ObjectDetector
    private lateinit var feedbackEngine: FeedbackEngine
    private lateinit var spatialSoundEngine: SpatialSoundEngine
    private lateinit var threatPrioritizer: ThreatPrioritizer
    private lateinit var voiceAssistant: VoiceAssistant
    private lateinit var locationHelper: LocationHelper
    private lateinit var fallDetector: FallDetector
    private lateinit var database: AppDatabase
    private val navigationManager = InAppNavigationManager()

    // Navigation Map Elements
    private var destinationMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var selectedPinLatLng: LatLng? = null
    private var currentViewMode = ViewMode.SPLIT
    private var isDetectionPaused = false
    private var emergencyContactPhone = "911"
    private var lastDbLogTime = 0L

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.VIBRATE,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.SEND_SMS
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

        setupAccessibleUiLayout(savedInstanceState)

        fallDetector = FallDetector(
            context = this,
            feedbackEngine = feedbackEngine,
            scope = lifecycleScope,
            onFallConfirmed = {
                voiceAssistant.sendEmergencySos("Automated Fall Detection Alert! User may be injured.")
            }
        )

        voiceAssistant = VoiceAssistant(
            context = this,
            feedbackEngine = feedbackEngine,
            locationHelper = locationHelper,
            database = database,
            coroutineScope = lifecycleScope,
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
                if (fallDetector.isCountdownActive) {
                    fallDetector.cancelFallAlert()
                } else {
                    feedbackEngine.speakNormal("No active emergency countdown.")
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
    }

    private fun loadUserPreferences() {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = database.detectionDao().getPreferences()
            if (prefs != null) {
                if (prefs.emergencyContactPhone.isNotBlank()) {
                    emergencyContactPhone = prefs.emergencyContactPhone
                }
                withContext(Dispatchers.Main) {
                    feedbackEngine.setSpeechRate(prefs.speechRate)
                }
            } else {
                database.detectionDao().savePreferences(
                    UserPreferences(
                        userId = 1,
                        speechRate = 1.15f,
                        vibrationIntensity = 2,
                        emergencyContactPhone = "911"
                    )
                )
            }
        }
    }

    private fun setupAccessibleUiLayout(savedInstanceState: Bundle?) {
        val rootLayout = FrameLayout(this).apply {
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
        val mapHintTextView = TextView(this).apply {
            text = "📍 Long-press anywhere on map to set walking destination"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B3000000"))
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

        // 3. Top Floating Overlays Container (HUD, Status, Location)
        val topOverlayContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 40, 24, 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        statusTextView = TextView(this).apply {
            text = "● PATH CLEAR  |  AI SCANNING"
            textSize = 14f
            setTextColor(Color.parseColor("#00E676"))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_status_pill)
            setPadding(32, 18, 32, 18)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, 12)
            }
            layoutParams = p
        }
        topOverlayContainer.addView(statusTextView)

        // Turn-by-turn Navigation HUD Card
        navHudLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_nav_hud)
            setPadding(24, 20, 24, 20)
            elevation = 16f
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            contentDescription = "Turn by turn walking instruction. Tap to hear directions."
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 12)
            }
            layoutParams = p
            setOnClickListener {
                announceCurrentDirection()
            }
        }

        navArrowTextView = TextView(this).apply {
            text = "⬆️"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_maneuver_badge)
            layoutParams = LinearLayout.LayoutParams(115, 115)
        }
        navHudLayout.addView(navArrowTextView)

        val navTextContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 16, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        navInstructionTextView = TextView(this).apply {
            text = "Walk straight"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        navTextContainer.addView(navInstructionTextView)

        navDistanceTextView = TextView(this).apply {
            text = "Calculating..."
            textSize = 13f
            setTextColor(Color.parseColor("#80D8FF"))
        }
        navTextContainer.addView(navDistanceTextView)

        navHudLayout.addView(navTextContainer)

        cancelNavButton = Button(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_cancel_circle)
            contentDescription = "Cancel walking navigation."
            layoutParams = LinearLayout.LayoutParams(100, 100)
            setOnClickListener {
                stopWalkingNavigation()
            }
        }
        navHudLayout.addView(cancelNavButton)

        topOverlayContainer.addView(navHudLayout)

        // Live location banner
        locationTextView = TextView(this).apply {
            text = "📍 GPS Locating..."
            textSize = 14f
            setTextColor(Color.parseColor("#00E5FF"))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_glass_card)
            setPadding(28, 16, 28, 16)
            elevation = 10f
            contentDescription = "Current location. Tap to announce aloud."
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 12)
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

        // Control Buttons Row: Mode Toggle + Pin Navigation
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        viewModeButton = Button(this).apply {
            text = "📷/🗺 SPLIT VIEW"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_primary_button)
            elevation = 8f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "Toggle display mode between Split View, Camera Only, or Map Only."
            layoutParams = LinearLayout.LayoutParams(0, 125, 1.0f).apply {
                setMargins(0, 0, 8, 0)
            }
            setOnClickListener {
                cycleViewMode()
            }
        }
        controlRow.addView(viewModeButton)

        navigatePinButton = Button(this).apply {
            text = "🎯 START NAV"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_secondary_button)
            elevation = 8f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "Start walking navigation to selected pin on map."
            layoutParams = LinearLayout.LayoutParams(0, 125, 1.0f).apply {
                setMargins(8, 0, 0, 0)
            }
            setOnClickListener {
                selectedPinLatLng?.let { pin ->
                    val targetLoc = Location("pin").apply {
                        latitude = pin.latitude
                        longitude = pin.longitude
                    }
                    startWalkingNavigation(targetLoc, "Selected Pin")
                } ?: run {
                    feedbackEngine.speakNormal("Please long-press a location on the map first to select your destination.")
                    Toast.makeText(this@MainActivity, "Long-press map to choose a destination", Toast.LENGTH_SHORT).show()
                }
            }
        }
        controlRow.addView(navigatePinButton)

        topOverlayContainer.addView(controlRow)
        rootLayout.addView(topOverlayContainer)

        // 4. Bottom Emergency SOS button
        emergencyButton = Button(this).apply {
            text = "🚨 EMERGENCY SOS"
            textSize = 20f
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_emergency_sos)
            elevation = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "Emergency SOS Button. Tap to send your GPS location and distress message to emergency contacts."
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                160
            ).apply {
                gravity = Gravity.BOTTOM
                setMargins(24, 0, 24, 36)
            }
            layoutParams = params
            setOnClickListener {
                voiceAssistant.sendEmergencySos("Manual SOS Button Pressed!")
            }
        }
        rootLayout.addView(emergencyButton)

        setContentView(rootLayout)
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

        map.setOnMapLongClickListener { latLng ->
            selectedPinLatLng = latLng
            destinationMarker?.remove()
            destinationMarker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Destination Pin")
                    .snippet("Tap START NAV to begin walking directions")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            )
            destinationMarker?.showInfoWindow()

            val targetLoc = Location("pin").apply {
                latitude = latLng.latitude
                longitude = latLng.longitude
            }
            startWalkingNavigation(targetLoc, "Selected Pin")
        }
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

    private fun startWalkingNavigation(targetLocation: Location, targetName: String) {
        val currentLoc = locationHelper.lastLocation ?: Location("user").apply {
            latitude = targetLocation.latitude - 0.0015
            longitude = targetLocation.longitude - 0.0015
        }

        val progress = navigationManager.startNavigation(currentLoc, targetLocation, targetName)
        drawRouteOnMap(currentLoc, targetLocation)
        updateNavHud(progress)

        val firstStep = progress.nextStep?.instruction ?: "Walk straight"
        val dist = progress.distanceToNextStepMeters.toInt()
        val speech = "Starting walking navigation to $targetName. In $dist meters, $firstStep."
        feedbackEngine.speakNormal(speech)
    }

    private fun stopWalkingNavigation() {
        navigationManager.stopNavigation()
        routePolyline?.remove()
        routePolyline = null
        destinationMarker?.remove()
        destinationMarker = null
        selectedPinLatLng = null
        navHudLayout.visibility = View.GONE
        feedbackEngine.speakNormal("Walking navigation stopped.")
    }

    private fun drawRouteOnMap(start: Location, target: Location) {
        val map = googleMap ?: return
        routePolyline?.remove()

        val points = mutableListOf<LatLng>()
        points.add(LatLng(start.latitude, start.longitude))
        navigationManager.currentRoute.forEach { step ->
            points.add(LatLng(step.targetLocation.latitude, step.targetLocation.longitude))
        }

        routePolyline = map.addPolyline(
            PolylineOptions()
                .addAll(points)
                .width(14f)
                .color(Color.parseColor("#00E5FF"))
                .geodesic(true)
        )

        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(start.latitude, start.longitude), 18f))
    }

    private fun updateNavHud(progress: NavProgress) {
        if (!navigationManager.isNavigating) {
            navHudLayout.visibility = View.GONE
            return
        }

        navHudLayout.visibility = View.VISIBLE
        navArrowTextView.text = progress.directionSymbol

        val step = progress.nextStep
        if (step != null) {
            val dist = progress.distanceToNextStepMeters.toInt()
            navInstructionTextView.text = "In ${dist}m: ${step.instruction}"
            navDistanceTextView.text = "${progress.totalRemainingDistanceMeters.toInt()}m remaining to ${navigationManager.destinationName}"
        } else if (progress.isArrived) {
            navInstructionTextView.text = "🎯 Arrived at Destination!"
            navDistanceTextView.text = "Navigation Complete"
        }
    }

    private fun announceCurrentDirection() {
        if (!navigationManager.isNavigating) {
            feedbackEngine.speakNormal("No active walking navigation. Long-press on the map to set a destination.")
            return
        }
        val loc = locationHelper.lastLocation ?: return
        val progress = navigationManager.updateProgress(loc)
        val step = progress.nextStep
        if (step != null) {
            val dist = progress.distanceToNextStepMeters.toInt()
            feedbackEngine.speakNormal("In $dist meters, ${step.instruction}. Total remaining distance: ${progress.totalRemainingDistanceMeters.toInt()} meters.")
        } else if (progress.isArrived) {
            feedbackEngine.speakNormal("You have arrived at your destination.")
        }
    }

    private fun initCameraAndServices() {
        cameraXManager = CameraXManager(
            context = this,
            lifecycleOwner = this,
            previewView = previewView,
            onLowLightDetected = { isDark ->
                if (isDark) {
                    runOnUiThread {
                        feedbackEngine.speakNormal("Low light detected. Consider saying: Light on, to improve visibility.")
                    }
                }
            },
            onFrameAnalyzed = { bitmap ->
                if (isDetectionPaused) return@CameraXManager

                val detections = objectDetector.detect(bitmap)

                runOnUiThread {
                    overlayView.updateDetections(detections)
                    if (detections.isEmpty()) {
                        statusTextView.text = "● PATH CLEAR  |  AI SCANNING"
                        statusTextView.setTextColor(Color.parseColor("#00E676"))
                    } else {
                        val top = detections[0]
                        val (prefix, color) = when (top.threatLevel) {
                            com.blindnav.app.ml.ThreatLevel.DANGER -> Pair("● DANGER", Color.parseColor("#FF1744"))
                            com.blindnav.app.ml.ThreatLevel.CAUTION -> Pair("● CAUTION", Color.parseColor("#FFD600"))
                            com.blindnav.app.ml.ThreatLevel.SAFE -> Pair("● DETECTED", Color.parseColor("#00E676"))
                        }
                        statusTextView.text = "$prefix: ${top.label.uppercase()}  ${String.format(java.util.Locale.US, "%.1f", top.distanceMeters)}m"
                        statusTextView.setTextColor(color)
                    }
                }

                threatPrioritizer.processFrameDetections(detections)
                logDetectionsToDatabase(detections)
            }
        )

        cameraXManager.startCamera()

        // Start continuous Google Maps-like location tracking
        locationHelper.startContinuousTracking(
            intervalMs = 4000L,
            minDisplacementMeters = 2.5f,
            onLocationUpdate = { loc, address, streetChanged ->
                runOnUiThread {
                    val bearingStr = if (loc.hasBearing()) " | ${locationHelper.getCompassDirection(loc.bearing)}" else ""
                    val speedKmh = (loc.speed * 3.6f).toInt()
                    val speedStr = if (speedKmh > 1) " | $speedKmh km/h" else ""
                    locationTextView.text = "📍 $address$bearingStr$speedStr"
                    locationTextView.contentDescription = "Current location: $address. Tap to announce."

                    // Center map on user if map is available
                    googleMap?.animateCamera(CameraUpdateFactory.newLatLng(LatLng(loc.latitude, loc.longitude)))

                    // Update in-app navigation if navigating
                    if (navigationManager.isNavigating) {
                        val progress = navigationManager.updateProgress(loc)
                        updateNavHud(progress)

                        val announcement = navigationManager.shouldAnnounceDirection(progress)
                        if (announcement != null) {
                            feedbackEngine.speakNormal(announcement)
                        }
                    }
                }

                if (streetChanged && !navigationManager.isNavigating) {
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
                voiceAssistant.startListening()
            }
        })

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
        if (::locationHelper.isInitialized) locationHelper.stopContinuousTracking()
        if (::cameraXManager.isInitialized) cameraXManager.shutdown()
        if (::objectDetector.isInitialized) objectDetector.close()
        if (::feedbackEngine.isInitialized) feedbackEngine.shutdown()
        if (::voiceAssistant.isInitialized) voiceAssistant.destroy()
    }
}
