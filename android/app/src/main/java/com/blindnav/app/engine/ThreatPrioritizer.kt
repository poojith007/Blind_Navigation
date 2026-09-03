package com.blindnav.app.engine

import com.blindnav.app.ml.DetectedObject
import com.blindnav.app.ml.ThreatLevel

class ThreatPrioritizer(
    private val feedbackEngine: IFeedbackEngine,
    private val spatialSoundEngine: SpatialSoundEngine? = null,
    private val alertCooldownMs: Long = 3200L,
    private val urgentAlertCooldownMs: Long = 2000L
) {

    private val lastAlertTimestamps = mutableMapOf<String, Long>()

    fun processFrameDetections(detections: List<DetectedObject>) {
        if (detections.isEmpty()) return

        // Sort by Threat Level (DANGER first), then by distance ascending
        val sortedDetections = detections.sortedWith(
            compareByDescending<DetectedObject> { it.threatLevel }
                .thenBy { it.distanceMeters }
        )

        val topThreat = sortedDetections.first()
        val now = System.currentTimeMillis()
        val objectKey = "${topThreat.label}_${topThreat.position}"
        val lastTimestamp = lastAlertTimestamps[objectKey] ?: 0L

        val isUrgent = topThreat.threatLevel == ThreatLevel.DANGER
        val requiredCooldown = if (isUrgent) urgentAlertCooldownMs else alertCooldownMs
        val isCooldownPassed = (now - lastTimestamp) > requiredCooldown

        // Instant spatial audio cue for reactive directional awareness
        spatialSoundEngine?.playSpatialCue(
            position = topThreat.position,
            threatLevel = topThreat.threatLevel,
            distanceMeters = topThreat.distanceMeters
        )

        if (isCooldownPassed) {
            lastAlertTimestamps[objectKey] = now
            dispatchFeedback(topThreat)
        }
    }

    private fun dispatchFeedback(target: DetectedObject) {
        val prompt = target.toTtsPrompt()

        when (target.threatLevel) {
            ThreatLevel.DANGER -> {
                feedbackEngine.speakUrgent(prompt)
                feedbackEngine.vibrateDanger()
            }
            ThreatLevel.CAUTION -> {
                feedbackEngine.speakNormal(prompt)
                feedbackEngine.vibrateCaution()
            }
            ThreatLevel.SAFE -> {
                feedbackEngine.speakNormal(prompt)
            }
        }
    }

    fun clearHistory() {
        lastAlertTimestamps.clear()
    }
}
