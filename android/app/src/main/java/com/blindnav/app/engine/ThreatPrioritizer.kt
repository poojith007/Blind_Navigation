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
 *
 * Every tunable threshold/cooldown lives in this constructor — nothing is scattered as a
 * hardcoded magic number deeper in the class — so these can be tuned through testing in one
 * place without hunting through the implementation.
 *
 * @param alertCooldownMs Minimum gap before re-announcing the SAME object, normal case.
 * @param urgentAlertCooldownMs Minimum gap before re-announcing the SAME object, DANGER/urgent case.
 * @param globalMinGapMs Minimum gap between ANY two obstacle alerts regardless of object, normal case.
 *   Without this, several distinct nearby objects — each individually respecting its own
 *   per-object cooldown — can still fire back-to-back and feel like constant nagging.
 * @param globalMinGapUrgentMs Same global floor, but for urgent/DANGER alerts.
 * @param minPersistenceFrames How many consecutive frames an object must be seen before its
 *   FIRST alert is allowed to fire. This is the temporal-confirmation filter: a single-frame
 *   misdetection (a shadow, a motion-blurred misclassification) will not by itself speak.
 * @param persistenceWindowMs How long a gap between sightings of the same object is still
 *   considered "continuous" for persistence counting, rather than resetting the streak.
 * @param immediateDangerConfidence A DANGER-level detection at or above this confidence is
 *   allowed to bypass the persistence requirement and alert on the very first frame — a
 *   genuinely imminent hazard must never be delayed waiting for a second confirming frame.
 * @param movedCloserDeltaMeters How much closer (meters) the same object must have gotten
 *   since its last alert to justify re-announcing before its cooldown has expired.
 * @param evasiveDirectionHoldMs Minimum time the left/right evasive recommendation is held
 *   before it's allowed to flip — without this, borderline per-frame noise flaps the
 *   direction back and forth, which both sounds wrong and re-triggers speech constantly.
 */
class ThreatPrioritizer(
    private val feedbackEngine: IFeedbackEngine,
    private val spatialSoundEngine: SpatialSoundEngine? = null,
    private val alertCooldownMs: Long = 4500L,
    private val urgentAlertCooldownMs: Long = 1800L,
    private val globalMinGapMs: Long = 2200L,
    private val globalMinGapUrgentMs: Long = 1100L,
    private val minPersistenceFrames: Int = 2,
    private val persistenceWindowMs: Long = 1200L,
    private val immediateDangerConfidence: Float = 0.55f,
    private val movedCloserDeltaMeters: Float = 1.0f,
    private val evasiveDirectionHoldMs: Long = 1000L
) {

    private val lastAlertTimestamps = mutableMapOf<String, Long>()
    private val lastAlertDistances = mutableMapOf<String, Float>()
    private val lastAlertThreatLevels = mutableMapOf<String, ThreatLevel>()
    private val lastAlertInstructions = mutableMapOf<String, String>()
    private var lastClearPathAnnouncementTime = 0L
    private var wasObstacleRecentlyActive = false

    private var lastEvasiveDirection: Position = Position.RIGHT
    private var lastEvasiveDirectionChangeTime = 0L

    private var lastAnyObstacleAlertTime = 0L

    // Temporal confirmation state: how many consecutive frames (within persistenceWindowMs
    // of each other) each object class has been continuously seen.
    private val persistenceCounts = mutableMapOf<String, Int>()
    private val persistenceLastSeen = mutableMapOf<String, Long>()

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
        val rawEvasiveDir = DistanceEstimator.determineEvasiveDirection(relevantDetections)
        val evasiveDir = if (rawEvasiveDir != lastEvasiveDirection && (now - lastEvasiveDirectionChangeTime) > evasiveDirectionHoldMs) {
            lastEvasiveDirection = rawEvasiveDir
            lastEvasiveDirectionChangeTime = now
            rawEvasiveDir
        } else {
            lastEvasiveDirection
        }
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

        // 8a. Temporal confirmation: has this object actually been seen persistently, or is
        // this possibly a single-frame misdetection? Update the consecutive-sightings streak
        // for this object class regardless of whether we end up alerting.
        val sinceLastSeen = now - (persistenceLastSeen[objectKeyFor(topThreat)] ?: 0L)
        val stillBeingSeen = sinceLastSeen <= persistenceWindowMs
        val persistenceStreak = if (stillBeingSeen) (persistenceCounts[objectKeyFor(topThreat)] ?: 0) + 1 else 1
        persistenceCounts[objectKeyFor(topThreat)] = persistenceStreak
        persistenceLastSeen[objectKeyFor(topThreat)] = now

        // 8b. Cooldown / Deduplication Rules:
        // Only repeat an alert when:
        // - the threat level increases
        // - the object moves significantly closer
        // - the recommended action changes
        // - the previous warning has expired
        // Keyed by object class only (not position): as the user walks past something, its
        // bucket shifts center -> left/right even though it's the same ongoing obstacle —
        // keying on position too caused a fresh "new object" alert for the same thing.
        val objectKey = objectKeyFor(topThreat)
        val lastTimestamp = lastAlertTimestamps[objectKey] ?: 0L
        val prevDistance = lastAlertDistances[objectKey] ?: Float.MAX_VALUE
        val prevThreatLevel = lastAlertThreatLevels[objectKey] ?: ThreatLevel.SAFE
        val prevInstruction = lastAlertInstructions[objectKey] ?: ""
        val timeSinceLastAlert = now - lastTimestamp
        val isFirstAlertForObject = lastTimestamp == 0L

        val threatLevelIncreased = topThreat.threatLevel.ordinal > prevThreatLevel.ordinal
        val movedSignificantlyCloser = (prevDistance - topThreat.distanceMeters) >= movedCloserDeltaMeters && topThreat.distanceMeters <= 2.5f
        val actionChanged = prevInstruction.isNotBlank() && prevInstruction != instructionText
        val requiredCooldown = if (isUrgent) urgentAlertCooldownMs else (if (isStationary) alertCooldownMs * 2 else alertCooldownMs)
        val warningExpired = timeSinceLastAlert > requiredCooldown
        val perObjectTrigger = warningExpired || threatLevelIncreased || movedSignificantlyCloser || actionChanged

        // Temporal confirmation gate: a first-time alert for this object needs either enough
        // consecutive confirming frames, or — for a genuinely imminent, high-confidence DANGER
        // — is allowed through immediately. Never equate low confidence with safety: the
        // bypass requires both DANGER proximity AND a real confidence floor, not just distance.
        val isConfidentImmediateDanger = topThreat.threatLevel == ThreatLevel.DANGER && topThreat.confidence >= immediateDangerConfidence
        val temporallyConfirmed = !isFirstAlertForObject || persistenceStreak >= minPersistenceFrames || isConfidentImmediateDanger

        // A fresh escalation to DANGER always gets through — safety first. Everything else
        // (including a first-time CAUTION alert) still has to respect the global pacing floor.
        val isEscalationToDanger = threatLevelIncreased && topThreat.threatLevel == ThreatLevel.DANGER
        val sinceAnyAlert = now - lastAnyObstacleAlertTime
        val globalGapOk = sinceAnyAlert > (if (isUrgent) globalMinGapUrgentMs else globalMinGapMs)

        if (perObjectTrigger && temporallyConfirmed && (isEscalationToDanger || globalGapOk)) {
            lastAlertTimestamps[objectKey] = now
            lastAlertDistances[objectKey] = topThreat.distanceMeters
            lastAlertThreatLevels[objectKey] = topThreat.threatLevel
            lastAlertInstructions[objectKey] = instructionText
            lastAnyObstacleAlertTime = now
            dispatchFeedback(decision)
        }

        return decision
    }

    private fun objectKeyFor(obj: DetectedObject): String = obj.label

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
        lastEvasiveDirection = Position.RIGHT
        lastEvasiveDirectionChangeTime = 0L
        lastAnyObstacleAlertTime = 0L
        persistenceCounts.clear()
        persistenceLastSeen.clear()
        latestDecision = null
    }
}