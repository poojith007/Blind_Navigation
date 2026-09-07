package com.blindnav.app.location

import android.location.Location
import java.util.Locale

data class NavStep(
    val instruction: String,
    val maneuver: ManeuverType,
    val symbol: String,
    val targetLocation: Location,
    val distanceMeters: Float = 0f
)

enum class ManeuverType {
    START,
    STRAIGHT,
    TURN_RIGHT,
    TURN_LEFT,
    SLIGHT_RIGHT,
    SLIGHT_LEFT,
    ARRIVE
}

data class RoutePreview(
    val destinationName: String,
    val destinationLocation: Location,
    val routeSteps: List<NavStep>,
    val totalDistanceMeters: Float,
    val estimatedMinutes: Int
)

data class NavProgress(
    val nextStep: NavStep?,
    val distanceToNextStepMeters: Float,
    val totalRemainingDistanceMeters: Float,
    val relativeBearingDegrees: Float,
    val directionSymbol: String,
    val isArrived: Boolean,
    val upcomingStep: NavStep? = null,
    val estimatedRemainingMinutes: Int = 0
)

class InAppNavigationManager {

    var isNavigating: Boolean = false
        private set

    var currentRoute: List<NavStep> = emptyList()
        private set

    var destinationName: String = ""
        private set

    var destinationLocation: Location? = null
        private set

    private var currentStepIndex: Int = 0
    private var lastSpokenStepIndex: Int = -1
    private var lastAnnouncementDistance: Float = Float.MAX_VALUE

    /**
     * Previews route, total distance, and estimated walking time before navigation starts.
     */
    fun previewRoute(
        start: Location,
        target: Location,
        targetName: String = "Destination"
    ): RoutePreview {
        val steps = generateWalkingSteps(start, target, targetName)
        val totalDist = start.distanceTo(target)
        val minutes = getEstimatedWalkingMinutes(totalDist)
        return RoutePreview(
            destinationName = targetName,
            destinationLocation = target,
            routeSteps = steps,
            totalDistanceMeters = totalDist,
            estimatedMinutes = minutes
        )
    }

    /**
     * Sets a new walking destination and generates route steps.
     */
    fun startNavigation(
        currentLocation: Location,
        targetLocation: Location,
        targetName: String = "Destination"
    ): NavProgress {
        destinationLocation = targetLocation
        destinationName = targetName
        isNavigating = true
        currentStepIndex = 0
        lastSpokenStepIndex = -1
        lastAnnouncementDistance = Float.MAX_VALUE

        currentRoute = generateWalkingSteps(currentLocation, targetLocation, targetName)
        return updateProgress(currentLocation)
    }

    /**
     * Starts navigation from an already generated RoutePreview.
     */
    fun startNavigation(
        currentLocation: Location,
        preview: RoutePreview
    ): NavProgress {
        destinationLocation = preview.destinationLocation
        destinationName = preview.destinationName
        isNavigating = true
        currentStepIndex = 0
        lastSpokenStepIndex = -1
        lastAnnouncementDistance = Float.MAX_VALUE

        currentRoute = preview.routeSteps
        return updateProgress(currentLocation)
    }

    fun getNextUpcomingStep(): NavStep? {
        val nextIdx = currentStepIndex + 1
        return if (nextIdx in currentRoute.indices) currentRoute[nextIdx] else null
    }

    fun getEstimatedWalkingMinutes(distanceMeters: Float): Int {
        if (distanceMeters <= 0f) return 0
        return Math.ceil((distanceMeters / 80.0).toDouble()).toInt().coerceAtLeast(1)
    }

    fun formatDistance(distanceMeters: Float): String {
        return if (distanceMeters >= 1000f) {
            String.format(Locale.US, "%.1f km", distanceMeters / 1000f)
        } else {
            "${distanceMeters.toInt()} m"
        }
    }

    /**
     * Updates the user's progress along the route given their current GPS position.
     */
    fun updateProgress(currentLocation: Location): NavProgress {
        if (!isNavigating || currentRoute.isEmpty() || currentStepIndex >= currentRoute.size) {
            return NavProgress(
                nextStep = null,
                distanceToNextStepMeters = 0f,
                totalRemainingDistanceMeters = 0f,
                relativeBearingDegrees = 0f,
                directionSymbol = "🎯",
                isArrived = true,
                upcomingStep = null,
                estimatedRemainingMinutes = 0
            )
        }

        val step = currentRoute[currentStepIndex]
        val distanceToStep = currentLocation.distanceTo(step.targetLocation)

        // If user reached the current waypoint (within 12 meters)
        if (distanceToStep < 12.0f && currentStepIndex < currentRoute.size - 1) {
            currentStepIndex++
            lastAnnouncementDistance = Float.MAX_VALUE
            return updateProgress(currentLocation)
        }

        // Check if arrived at final destination (within 8 meters)
        val finalLoc = destinationLocation ?: step.targetLocation
        val distanceToDestination = currentLocation.distanceTo(finalLoc)
        val isArrived = distanceToDestination < 8.0f || (currentStepIndex == currentRoute.size - 1 && distanceToStep < 8.0f)

        if (isArrived) {
            isNavigating = false
            return NavProgress(
                nextStep = currentRoute.lastOrNull(),
                distanceToNextStepMeters = 0f,
                totalRemainingDistanceMeters = 0f,
                relativeBearingDegrees = 0f,
                directionSymbol = "🎯",
                isArrived = true,
                upcomingStep = null,
                estimatedRemainingMinutes = 0
            )
        }

        // Calculate relative bearing to target
        val bearingToTarget = currentLocation.bearingTo(step.targetLocation)
        val userBearing = if (currentLocation.hasBearing()) currentLocation.bearing else bearingToTarget
        var diff = bearingToTarget - userBearing
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f

        val symbol = getManeuverSymbol(diff, step.maneuver)
        val upcoming = getNextUpcomingStep()
        val estMinutes = getEstimatedWalkingMinutes(distanceToDestination)

        return NavProgress(
            nextStep = step,
            distanceToNextStepMeters = distanceToStep,
            totalRemainingDistanceMeters = distanceToDestination,
            relativeBearingDegrees = diff,
            directionSymbol = symbol,
            isArrived = false,
            upcomingStep = upcoming,
            estimatedRemainingMinutes = estMinutes
        )
    }

    /**
     * Evaluates if a voice direction announcement should be spoken right now.
     */
    fun shouldAnnounceDirection(progress: NavProgress): String? {
        if (!isNavigating || progress.nextStep == null) {
            if (progress.isArrived && lastSpokenStepIndex != 9999) {
                lastSpokenStepIndex = 9999
                return "You have arrived at your destination, $destinationName."
            }
            return null
        }

        val step = progress.nextStep
        val dist = progress.distanceToNextStepMeters.toInt()

        // 1. Announce initial step when navigation begins or step changes
        if (currentStepIndex != lastSpokenStepIndex) {
            lastSpokenStepIndex = currentStepIndex
            lastAnnouncementDistance = progress.distanceToNextStepMeters
            return "${step.instruction}. Walk straight for $dist meters."
        }

        // 2. Advance announcement when approaching turn (between 25 and 35 meters)
        if (dist in 25..35 && lastAnnouncementDistance > 40f) {
            lastAnnouncementDistance = progress.distanceToNextStepMeters
            return "In $dist meters, ${step.instruction}."
        }

        // 3. Immediate maneuver warning when turn is right ahead (between 8 and 15 meters)
        if (dist in 8..15 && lastAnnouncementDistance > 20f) {
            lastAnnouncementDistance = progress.distanceToNextStepMeters
            return "Prepare to ${step.instruction}."
        }

        return null
    }

    fun stopNavigation() {
        isNavigating = false
        currentRoute = emptyList()
        destinationLocation = null
        destinationName = ""
        currentStepIndex = 0
        lastSpokenStepIndex = -1
    }

    /**
     * Generates a multi-step walking route connecting user location to target location.
     */
    private fun generateWalkingSteps(
        start: Location,
        target: Location,
        targetName: String
    ): List<NavStep> {
        val totalDistance = start.distanceTo(target)
        val bearing = start.bearingTo(target)

        // For short distances (< 60 meters), single straight segment
        if (totalDistance < 60f) {
            return listOf(
                NavStep(
                    instruction = "Walk straight toward $targetName",
                    maneuver = ManeuverType.STRAIGHT,
                    symbol = "⬆️",
                    targetLocation = target,
                    distanceMeters = totalDistance
                )
            )
        }

        // For longer walking distances, create intermediate turn points
        val midPoint = Location("nav").apply {
            latitude = (start.latitude + target.latitude) / 2.0
            longitude = start.longitude // Right-angle walking grid
        }

        val step1 = NavStep(
            instruction = "Walk toward the first turn",
            maneuver = ManeuverType.STRAIGHT,
            symbol = "⬆️",
            targetLocation = midPoint,
            distanceMeters = start.distanceTo(midPoint)
        )

        val turnBearing = midPoint.bearingTo(target)
        val turnManeuver = if (turnBearing > bearing) ManeuverType.TURN_RIGHT else ManeuverType.TURN_LEFT
        val turnSymbol = if (turnManeuver == ManeuverType.TURN_RIGHT) "➡️" else "⬅️"

        val step2 = NavStep(
            instruction = if (turnManeuver == ManeuverType.TURN_RIGHT) "Turn right toward $targetName" else "Turn left toward $targetName",
            maneuver = turnManeuver,
            symbol = turnSymbol,
            targetLocation = target,
            distanceMeters = midPoint.distanceTo(target)
        )

        return listOf(step1, step2)
    }

    private fun getManeuverSymbol(bearingDiff: Float, baseManeuver: ManeuverType): String {
        return when {
            bearingDiff in -25.0f..25.0f -> "⬆️"
            bearingDiff in 25.0f..70.0f -> "↗️"
            bearingDiff in 70.0f..120.0f -> "➡️"
            bearingDiff in -70.0f..-25.0f -> "↖️"
            bearingDiff in -120.0f..-70.0f -> "⬅️"
            else -> "🔄"
        }
    }
}
