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
    val audioEvidence: AudioEvidence? = null,
    val isPathUnclear: Boolean = false
)

/**
 * Multi-Sensor Path Guidance & Decision Engine.
 * Fuses visual detections, walking corridor geometry, environmental acoustic evidence,
 * and GPS navigation to provide calm, actionable path instructions.
 *
 * Implements context-aware obstacle guidance:
 * 1. What object was detected?
 * 2. How far away is it?
 * 3. Where is it relative to the user?
 * 4. Is it actually inside the walking corridor?
 * 5. Is it stationary or potentially moving?
 * 6. Does it affect the user's route?
 * 7. Is there a safer direction?
 * 8. How urgent is the situation?
 */
class ThreatPrioritizer(
    private val feedbackEngine: IFeedbackEngine,
    private val spatialSoundEngine: SpatialSoundEngine? = null,
    private val alertCooldownMs: Long = 4500L,
    private val urgentAlertCooldownMs: Long = 1800L
) {

    private val lastAlertTimestamps = mutableMapOf<String, Long>()
    private val lastAlertDistances = mutableMapOf<String, Float>()
    private val lastAlertThreatLevels = mutableMapOf<String, ThreatLevel>()
    private val lastAlertInstructions = mutableMapOf<String, String>()
    private var lastClearPathAnnouncementTime = 0L
    private var wasObstacleRecentlyActive = false

    var latestDecision: GuidanceDecision? = null
        private set

    /**
     * Backward-compatible entrypoint returning the guidance decision.
     */
    fun processFrameDetections(detections: List<DetectedObject>): GuidanceDecision? {
        return processFrameDetections(detections, null, null, true, false)
    }

    /**
     * Full multi-sensor evaluation combining camera vision, walking corridor,
     * environmental audio evidence, navigation progress, and safe failure guards.
     */
    fun processFrameDetections(
        detections: List<DetectedObject>,
        audioEvidence: AudioEvidence? = null,
        navProgress: NavProgress? = null,
        isUserMoving: Boolean = true,
        isPerceptionDegraded: Boolean = false
    ): GuidanceDecision? {
        val now = System.currentTimeMillis()

        // 10. SAFE FAILURE MODE: Never communicate "PATH CLEAR" if perception is degraded
        if (isPerceptionDegraded) {
            val decision = GuidanceDecision(
                priority = SpeechPriority.SAFETY_WARNING,
                instruction = "Path unclear. Please stop.",
                visualHeadline = "PATH UNCLEAR",
                isObstaclePresent = false,
                isPathUnclear = true
            )
            latestDecision = decision
            if (now - (lastAlertTimestamps["SAFE_FAILURE"] ?: 0L) > 8000L) {
                lastAlertTimestamps["SAFE_FAILURE"] = now
                dispatchFeedback(decision)
            }
            return decision
        }

        val isStationary = !isUserMoving

        // 1. Evaluate Environmental Audio Events (horns, sirens, bells)
        val isAcousticHorn = audioEvidence?.type == AudioEventType.VEHICLE_HORN && audioEvidence.isRecent()
        val isAcousticSiren = audioEvidence?.type == AudioEventType.SIREN && audioEvidence.isRecent()
        val isAcousticBell = audioEvidence?.type == AudioEventType.BICYCLE_BELL && audioEvidence.isRecent()

        // 2. Filter detections relevant to walking corridor or high-speed hazards
        val relevantDetections = detections.filter { obj ->
            val isVehicleOrHazard = obj.label.lowercase() in listOf("car", "bus", "truck", "motorcycle", "bicycle")
            obj.isInCorridor || (isVehicleOrHazard && obj.distanceMeters <= 3.5f) || (obj.threatLevel == ThreatLevel.DANGER)
        }

        // 3. Normal state: PATH CLEAR
        if (relevantDetections.isEmpty()) {
            if (isAcousticSiren && (now - (lastAlertTimestamps["SIREN_EVENT"] ?: 0L)) > 10000L) {
                lastAlertTimestamps["SIREN_EVENT"] = now
                val decision = GuidanceDecision(
                    priority = SpeechPriority.SAFETY_WARNING,
                    instruction = "Emergency siren detected nearby. Exercise caution.",
                    visualHeadline = "SIREN DETECTED NEARBY",
                    isObstaclePresent = false,
                    audioEvidence = audioEvidence
                )
                latestDecision = decision
                dispatchFeedback(decision)
                return decision
            }

            // Normal state: If an obstacle was active and now cleared, reassure user
            if (wasObstacleRecentlyActive && (now - lastClearPathAnnouncementTime) > 7000L) {
                wasObstacleRecentlyActive = false
                lastClearPathAnnouncementTime = now
                val navActive = navProgress?.nextStep != null && !navProgress.isArrived
                val clearText = if (navActive) "Path clear. Continue straight." else "Continue straight."
                val decision = GuidanceDecision(
                    priority = SpeechPriority.INFORMATION,
                    instruction = clearText,
                    visualHeadline = "PATH CLEAR",
                    isObstaclePresent = false
                )
                latestDecision = decision
                dispatchFeedback(decision)
                return decision
            }

            val idleDecision = GuidanceDecision(
                priority = SpeechPriority.INFORMATION,
                instruction = "",
                visualHeadline = "PATH CLEAR",
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
        val isVehicleType = topThreat.label.lowercase() in listOf("car", "bus", "truck", "motorcycle")
        if (isVehicleType && (isAcousticHorn || audioEvidence?.type == AudioEventType.ENGINE_RUMBLE)) {
            topThreat = topThreat.copy(threatLevel = ThreatLevel.DANGER)
        } else if (topThreat.label.lowercase() == "bicycle" && isAcousticBell) {
            topThreat = topThreat.copy(threatLevel = ThreatLevel.DANGER)
        }

        wasObstacleRecentlyActive = true

        // 6 & 7. Determine Evasive Maneuver & Actionable Guidance
        val evasiveDir = DistanceEstimator.determineEvasiveDirection(relevantDetections)
        val instructionText = topThreat.toActionableInstruction(evasiveDir)

        val isUrgent = topThreat.threatLevel == ThreatLevel.DANGER || topThreat.distanceMeters <= 1.2f
        val priority = if (isUrgent) SpeechPriority.EMERGENCY else SpeechPriority.SAFETY_WARNING

        val headline = if (isUrgent) {
            "STOP. IMMEDIATE OBSTACLE"
        } else {
            "CAUTION. OBSTACLE AHEAD"
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

        // Spatial audio cue for reactive directional awareness
        spatialSoundEngine?.playSpatialCue(
            position = topThreat.position,
            threatLevel = topThreat.threatLevel,
            distanceMeters = topThreat.distanceMeters
        )

        // 8. Cooldown / Deduplication Rules:
        // Only repeat an alert when:
        // - the threat level increases
        // - the object moves significantly closer (by >= 0.7m)
        // - the recommended action changes
        // - the previous warning has expired
        val objectKey = "${topThreat.label}_${topThreat.position}"
        val lastTimestamp = lastAlertTimestamps[objectKey] ?: 0L
        val prevDistance = lastAlertDistances[objectKey] ?: Float.MAX_VALUE
        val prevThreatLevel = lastAlertThreatLevels[objectKey] ?: ThreatLevel.SAFE
        val prevInstruction = lastAlertInstructions[objectKey] ?: ""
        val timeSinceLastAlert = now - lastTimestamp

        val threatLevelIncreased = topThreat.threatLevel.ordinal > prevThreatLevel.ordinal
        val movedSignificantlyCloser = (prevDistance - topThreat.distanceMeters) >= 0.7f && topThreat.distanceMeters <= 2.5f
        val actionChanged = prevInstruction.isNotBlank() && prevInstruction != instructionText
        val requiredCooldown = if (isUrgent) urgentAlertCooldownMs else (if (isStationary) alertCooldownMs * 2 else alertCooldownMs)
        val warningExpired = timeSinceLastAlert > requiredCooldown

        if (warningExpired || threatLevelIncreased || movedSignificantlyCloser || actionChanged) {
            lastAlertTimestamps[objectKey] = now
            lastAlertDistances[objectKey] = topThreat.distanceMeters
            lastAlertThreatLevels[objectKey] = topThreat.threatLevel
            lastAlertInstructions[objectKey] = instructionText
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
        lastAlertThreatLevels.clear()
        lastAlertInstructions.clear()
        wasObstacleRecentlyActive = false
        latestDecision = null
    }
}
