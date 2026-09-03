package com.blindnav.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.blindnav.app.engine.IFeedbackEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class FallDetector(
    private val context: Context,
    private val feedbackEngine: IFeedbackEngine,
    private val scope: CoroutineScope,
    private val onFallConfirmed: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var countdownJob: Job? = null
    var isCountdownActive = false
        private set

    // Algorithm thresholds (in m/s^2)
    private val freeFallThreshold = 4.5f // ~0.45g
    private val impactThreshold = 26.0f  // ~2.65g
    
    private var freeFallDetectedTime = 0L

    fun start() {
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        cancelFallAlert()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        if (isCountdownActive) return // Already in countdown alert

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val totalAcceleration = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        val now = System.currentTimeMillis()

        // 1. Detect Free-fall phase
        if (totalAcceleration < freeFallThreshold) {
            freeFallDetectedTime = now
        }

        // 2. Detect Impact Spike (occurring within 1000ms of free-fall or sudden violent impact)
        val isImpact = totalAcceleration > impactThreshold
        val isRecentFreeFall = (now - freeFallDetectedTime) < 1000L

        if (isImpact && (isRecentFreeFall || totalAcceleration > 32.0f)) {
            freeFallDetectedTime = 0L
            initiateFallAlert()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun initiateFallAlert() {
        if (isCountdownActive) return
        isCountdownActive = true

        feedbackEngine.vibrateDanger()
        feedbackEngine.speakUrgent("Warning: Fall detected! Emergency SOS will trigger in 10 seconds. Double tap or say Cancel to abort.")

        countdownJob = scope.launch(Dispatchers.Main) {
            var secondsRemaining = 10
            while (secondsRemaining > 0) {
                delay(1000L)
                secondsRemaining--

                if (!isCountdownActive) return@launch

                if (secondsRemaining in listOf(7, 5, 3, 2, 1)) {
                    feedbackEngine.speakUrgent("$secondsRemaining...")
                    feedbackEngine.vibrateCaution()
                }
            }

            // Countdown finished without cancellation -> trigger emergency
            if (isCountdownActive) {
                isCountdownActive = false
                feedbackEngine.speakUrgent("Sending emergency fall alert!")
                onFallConfirmed()
            }
        }
    }

    fun cancelFallAlert() {
        if (isCountdownActive) {
            isCountdownActive = false
            countdownJob?.cancel()
            countdownJob = null
            feedbackEngine.speakNormal("Fall alert cancelled. You are safe.")
        }
    }
}
