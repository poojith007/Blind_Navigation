package com.blindnav.app.engine

import com.blindnav.app.location.NavProgress
import com.blindnav.app.ml.DetectedObject
import com.blindnav.app.ml.DistanceEstimator
import com.blindnav.app.ml.Position
import com.blindnav.app.ml.ThreatLevel

data class GuidanceDecision(
    val priority: SpeechPriority,
    val instruction: String,
    val visualHeadline: String,
    val isObstaclePresent: Boolean,
    val targetObject: DetectedObject? = null,
    val audioEvidence: AudioEvidence? = null
)

/**
 * Multi-Sensor Path Guidance & Decision Engine.
 * Fuses visual detections, walking corridor geometry, environmental acoustic evidence,
 * and GPS navigation to provide calm, actionable path instructions.
 */
class ThreatPrioritizer(
    private val feedbackEngine: IFeedbackEngine,
    private val spatialSoundEngine: SpatialSoundEngine? = null,
    private val alertCooldownMs: Long = 4500L,
    private val urgentAlertCooldownMs: Long = 1800L
) {

    private val lastAlertTimestamps = mutableMapOf<String, Long>()
    private val lastAlertDistances = mutableMapOf<String, Float>()
    private var lastClearPathAnnouncementTime = 0L
    private var wasObstacleRecentlyActive = false

    var latestDecision: GuidanceDecision? = null
        private set

    /**
     * Backward-compatible entrypoint returning the guidance decision.
     */
    fun processFrameDetections(detections: List<DetectedObject>): GuidanceDecision? {
        return processFrameDetections(detections, null, null, true)
    }

    /**
     * Full multi-sensor evaluation combining camera vision, walking corridor,
     * environmental audio evidence, and navigation progress.
     */
    fun processFrameDetections(
        detections: List<DetectedObject>,
        audioEvidence: AudioEvidence? = null,
        navProgress: NavProgress? = null,
        isUserMoving: Boolean = true
    ): GuidanceDecision? {
        val now = System.currentTimeMillis()

        // If user is standing completely still and no moving hazards, suppress low-urgency alerts
        val isStationary = !isUserMoving

        // 1. Evaluate Environmental Audio Events (horns, sirens)
        val isAcousticHorn = audioEvidence?.type == AudioEventType.VEHICLE_HORN && audioEvidence.isRecent()
        val isAcousticSiren = audioEvidence?.type == AudioEventType.SIREN && audioEvidence.isRecent()
        val isAcousticBell = audioEvidence?.type == AudioEventType.BICYCLE_BELL && audioEvidence.isRecent()

        // 2. Filter detections relevant to the walking corridor or high-speed hazards
        // Objects far outside the corridor (e.g. far left chair at 3.5m) are filtered out
        val relevantDetections = detections.filter { obj ->
            val isVehicleOrHazard = obj.label.lowercase() in listOf("car", "bus", "truck", "motorcycle", "bicycle")
            // In walking corridor, OR close vehicle approaching from sides
            obj.isInCorridor || (isVehicleOrHazard && obj.distanceMeters <= 3.5f) || (obj.threatLevel == ThreatLevel.DANGER)
        }

        // 3. If no relevant obstacles are detected
        if (relevantDetections.isEmpty()) {
            // Check if siren is blaring nearby without visual lock
            if (isAcousticSiren && (now - (lastAlertTimestamps["SIREN_EVENT"] ?: 0L)) > 10000L) {
                lastAlertTimestamps["SIREN_EVENT"] = now
                val decision = GuidanceDecision(
                    priority = SpeechPriority.SAFETY_WARNING,
                    instruction = "Emergency siren detected nearby. Exercise caution.",
                    visualHeadline = "🚨 SIREN DETECTED NEARBY",
                    isObstaclePresent = false,
                    audioEvidence = audioEvidence
                )
                latestDecision = decision
                dispatchFeedback(decision)
                return decision
            }

            // Path is clear! If user was previously avoiding an obstacle, provide reassurance
            if (wasObstacleRecentlyActive && (now - lastClearPathAnnouncementTime) > 6000L) {
                wasObstacleRecentlyActive = false
                lastClearPathAnnouncementTime = now
                val navActive = navProgress?.nextStep != null && !navProgress.isArrived
                val clearText = if (navActive) "Path clear. Continue following directions." else "Path clear. Continue straight."
                val decision = GuidanceDecision(
                    priority = SpeechPriority.INFORMATION,
                    instruction = clearText,
                    visualHeadline = "● PATH CLEAR",
                    isObstaclePresent = false
                )
                latestDecision = decision
                dispatchFeedback(decision)
                return decision
            }

            val idleDecision = GuidanceDecision(
                priority = SpeechPriority.INFORMATION,
                instruction = "",
                visualHeadline = "● PATH CLEAR",
                isObstaclePresent = false
            )
            latestDecision = idleDecision
            return idleDecision
        }

        // 4. Prioritize obstacles: DANGER first, then distance ascending
        val sortedDetections = relevantDetections.sortedWith(
            compareByDescending<DetectedObject> { it.threatLevel }
                .thenBy { it.distanceMeters }
        )

        var topThreat = sortedDetections.first()

        // 5. Multi-Sensor Fusion: Correlate acoustic evidence with visual detection
        // If vehicle detected on side AND vehicle horn/rumble heard -> escalate to immediate DANGER!
        val isVehicleType = topThreat.label.lowercase() in listOf("car", "bus", "truck", "motorcycle")
        if (isVehicleType && (isAcousticHorn || audioEvidence?.type == AudioEventType.ENGINE_RUMBLE)) {
            topThreat = topThreat.copy(threatLevel = ThreatLevel.DANGER)
        } else if (topThreat.label.lowercase() == "bicycle" && isAcousticBell) {
            topThreat = topThreat.copy(threatLevel = ThreatLevel.DANGER)
        }

        wasObstacleRecentlyActive = true

        // 6. Determine Evasive Maneuver & Actionable Guidance
        val evasiveDir = DistanceEstimator.determineEvasiveDirection(relevantDetections)
        val instructionText = topThreat.toActionableInstruction(evasiveDir)

        val isUrgent = topThreat.threatLevel == ThreatLevel.DANGER || topThreat.distanceMeters <= 1.2f
        val priority = if (isUrgent) SpeechPriority.EMERGENCY else SpeechPriority.SAFETY_WARNING

        val headline = if (isUrgent) {
            "🛑 STOP - OBSTACLE AHEAD"
        } else {
            val dirWord = if (evasiveDir == Position.LEFT) "MOVE LEFT" else "MOVE RIGHT"
            "⚠️ OBSTACLE AHEAD - $dirWord"
        }

        val decision = GuidanceDecision(
            priority = priority,
            instruction = instructionText,
            visualHeadline = headline,
            isObstaclePresent = true,
            targetObject = topThreat,
            audioEvidence = audioEvidence
        )
        latestDecision = decision

        // 7. Instant spatial audio cue for reactive directional awareness
        spatialSoundEngine?.playSpatialCue(
            position = topThreat.position,
            threatLevel = topThreat.threatLevel,
            distanceMeters = topThreat.distanceMeters
        )

        // 8. Cooldown and Escalation Check
        val objectKey = "${topThreat.label}_${topThreat.position}"
        val lastTimestamp = lastAlertTimestamps[objectKey] ?: 0L
        val prevDistance = lastAlertDistances[objectKey] ?: Float.MAX_VALUE
        val timeSinceLastAlert = now - lastTimestamp

        // Escalation: if obstacle moved significantly closer (drops by >= 0.7m and <= 1.5m), escalate immediately!
        val isEscalated = (prevDistance - topThreat.distanceMeters) >= 0.7f && topThreat.distanceMeters <= 1.5f
        val requiredCooldown = if (isUrgent) urgentAlertCooldownMs else (if (isStationary) alertCooldownMs * 2 else alertCooldownMs)
        val isCooldownPassed = timeSinceLastAlert > requiredCooldown

        if (isCooldownPassed || isEscalated) {
            lastAlertTimestamps[objectKey] = now
            lastAlertDistances[objectKey] = topThreat.distanceMeters
            dispatchFeedback(decision)
        }

        return decision
    }

    private fun dispatchFeedback(decision: GuidanceDecision) {
        if (decision.instruction.isBlank()) return

        feedbackEngine.speakWithPriority(decision.instruction, decision.priority)

        when (decision.priority) {
            SpeechPriority.EMERGENCY -> feedbackEngine.vibrateDanger()
            SpeechPriority.SAFETY_WARNING -> feedbackEngine.vibrateCaution()
            else -> {}
        }
    }

    fun clearHistory() {
        lastAlertTimestamps.clear()
        lastAlertDistances.clear()
        wasObstacleRecentlyActive = false
        latestDecision = null
    }
}
