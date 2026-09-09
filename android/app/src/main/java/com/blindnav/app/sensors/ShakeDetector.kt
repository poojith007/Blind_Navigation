package com.blindnav.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Shake detector for blind and visually impaired users.
 * Enables hands-free voice assistant triggering by gently shaking the device.
 */
class ShakeDetector(
    context: Context,
    private val shakeSlopTimeMs: Long = 500L,
    private val minShakeCount: Int = 2,
    private val cooldownTimeMs: Long = 1200L,
    private val onShake: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var shakeTimestamp = 0L
    private var shakeCount = 0

    fun start() {
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val triggered = processAcceleration(
            x = event.values[0],
            y = event.values[1],
            z = event.values[2],
            now = System.currentTimeMillis()
        )

        if (triggered) {
            onShake()
        }
    }

    /**
     * Pure testable logic for detecting intentional shake patterns.
     */
    fun processAcceleration(x: Float, y: Float, z: Float, now: Long): Boolean {
        val gX = x / SensorManager.GRAVITY_EARTH
        val gY = y / SensorManager.GRAVITY_EARTH
        val gZ = z / SensorManager.GRAVITY_EARTH

        // Total G-force magnitude
        val gForce = sqrt((gX * gX + gY * gY + gZ * gZ).toDouble()).toFloat()

        // Threshold of ~2.1g indicates intentional shake
        if (gForce > 2.1f) {
            if (shakeTimestamp + shakeSlopTimeMs > now) {
                shakeCount++
                if (shakeCount >= minShakeCount) {
                    shakeTimestamp = now + cooldownTimeMs
                    shakeCount = 0
                    return true
                }
            } else {
                shakeCount = 1
                shakeTimestamp = now
            }
        }
        return false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
