package com.blindnav.app

import android.Manifest
import android.content.contentValuesOf
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
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
import com.blindnav.app.engine.FeedbackEngine
import com.blindnav.app.engine.ThreatPrioritizer
import com.blindnav.app.ml.ObjectDetector
import com.blindnav.app.ui.BoundingBoxOverlayView
import com.blindnav.app.voice.VoiceAssistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: BoundingBoxOverlayView
    private lateinit var statusTextView: TextView
    private lateinit var emergencyButton: Button

    private lateinit var cameraXManager: CameraXManager
    private lateinit var objectDetector: ObjectDetector
    private lateinit var feedbackEngine: FeedbackEngine
    private lateinit var threatPrioritizer: ThreatPrioritizer
    private lateinit var voiceAssistant: VoiceAssistant
    private lateinit var database: AppDatabase

    private var isDetectionPaused = false
    private var emergencyContactPhone = "911"

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.VIBRATE,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.SEND_SMS
    )
    private val permissionRequestCode = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setupAccessibleUiLayout()

        feedbackEngine = FeedbackEngine(this)
        threatPrioritizer = ThreatPrioritizer(feedbackEngine)
        objectDetector = ObjectDetector(this)
        database = AppDatabase.getDatabase(this)

        voiceAssistant = VoiceAssistant(this, feedbackEngine) { emergencyContactPhone }

        if (allPermissionsGranted()) {
            initCameraAndServices()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions, permissionRequestCode)
        }

        setupGestureListener()
    }

    private fun setupAccessibleUiLayout() {
        val rootLayout = FrameLayout(this).apply {
            contentDescription = "Blind Navigation Main Screen. Double tap to pause detection. Long press for emergency alert."
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(previewView)

        overlayView = BoundingBoxOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(overlayView)

        statusTextView = TextView(this).apply {
            text = "AI System Active - Scanning..."
            textSize = 20f
            setTextColor(android.graphics.Color.YELLOW)
            setBackgroundColor(android.graphics.Color.parseColor("#99000000"))
            setPadding(32, 48, 32, 32)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rootLayout.addView(statusTextView)

        emergencyButton = Button(this).apply {
            text = "🚨 EMERGENCY SOS"
            textSize = 22f
            setBackgroundColor(android.graphics.Color.RED)
            setTextColor(android.graphics.Color.WHITE)
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                160
            ).apply {
                gravity = android.view.Gravity.BOTTOM
                setMargins(32, 32, 32, 48)
            }
            layoutParams = params
            setOnClickListener {
                voiceAssistant.sendEmergencySos("Manual SOS Button Pressed!")
            }
        }
        rootLayout.addView(emergencyButton)

        setContentView(rootLayout)
    }

    private fun initCameraAndServices() {
        cameraXManager = CameraXManager(this, this, previewView) { bitmap ->
            if (isDetectionPaused) return@CameraXManager

            val detections = objectDetector.detect(bitmap)

            runOnUiThread {
                overlayView.updateDetections(detections)
                statusTextView.text = if (detections.isEmpty()) {
                    "Path Clear - Scanning..."
                } else {
                    "Detected ${detections.size} objects | Top: ${detections[0].label}"
                }
            }

            threatPrioritizer.processFrameDetections(detections)
            logDetectionsToDatabase(detections)
        }

        cameraXManager.startCamera()
        feedbackEngine.speakNormal("Blind Navigation System Ready. Camera Scanning Active.")
    }

    private fun logDetectionsToDatabase(detections: List<com.blindnav.app.ml.DetectedObject>) {
        if (detections.isEmpty()) return
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
            override fun onDoubleTap(e: MotionEvent): Boolean {
                isDetectionPaused = !isDetectionPaused
                val msg = if (isDetectionPaused) "Detection Paused." else "Detection Resumed."
                feedbackEngine.speakNormal(msg)
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
            } else {
                Toast.makeText(this, "Permissions required for Blind Nav App", Toast.LENGTH_LONG).show()
                feedbackEngine.speakUrgent("Camera and Location permissions are required for the app to function.")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraXManager.isInitialized) cameraXManager.shutdown()
        if (::objectDetector.isInitialized) objectDetector.close()
        if (::feedbackEngine.isInitialized) feedbackEngine.shutdown()
        if (::voiceAssistant.isInitialized) voiceAssistant.destroy()
    }
}
