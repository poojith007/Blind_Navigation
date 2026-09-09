package com.blindnav.app.ui

/**
 * Formal UI states representing the assistive navigation interface.
 * Implements the 14 required product states with dual encoding (icons + text).
 */
enum class NavigationUiState(
    val stateName: String,
    val displayIcon: String,
    val defaultHeadline: String,
    val isSafetyCritical: Boolean = false
) {
    // 1. Initial calm state before navigation
    HOME(
        stateName = "Home",
        displayIcon = "●",
        defaultHeadline = "SYSTEM READY  |  CALM GUIDANCE"
    ),

    // 2. Searching for a walking destination
    DESTINATION_SEARCH(
        stateName = "Destination Search",
        displayIcon = "🔍",
        defaultHeadline = "SEARCH DESTINATION"
    ),

    // 3. Route calculated, awaiting confirmation
    ROUTE_PREVIEW(
        stateName = "Route Preview",
        displayIcon = "📍",
        defaultHeadline = "ROUTE PREVIEW"
    ),

    // 4. Navigation active with clear path ahead ("Continue straight")
    NAV_PATH_CLEAR(
        stateName = "Navigation Active",
        displayIcon = "●",
        defaultHeadline = "PATH CLEAR  |  NAVIGATING"
    ),

    // 5. Approaching an upcoming turn ("Turn right in 20 m")
    NAV_APPROACHING_TURN(
        stateName = "Approaching Turn",
        displayIcon = "↗️",
        defaultHeadline = "UPCOMING TURN"
    ),

    // 6. Non-critical obstacle in corridor ("Obstacle ahead - Move slightly left")
    OBSTACLE_WARNING(
        stateName = "Obstacle Warning",
        displayIcon = "⚠️",
        defaultHeadline = "CAUTION: OBSTACLE AHEAD",
        isSafetyCritical = true
    ),

    // 7. Imminent collision risk requiring immediate halt ("STOP")
    CRITICAL_STOP(
        stateName = "Critical Stop",
        displayIcon = "🛑",
        defaultHeadline = "STOP IMMEDIATELY",
        isSafetyCritical = true
    ),

    // 8. User arrived at the final target location
    DESTINATION_REACHED(
        stateName = "Destination Reached",
        displayIcon = "🎯",
        defaultHeadline = "ARRIVED AT DESTINATION"
    ),

    // 9. Safe Failure: Perception confidence insufficient ("Path unclear. Please stop.")
    PATH_UNCLEAR(
        stateName = "Path Unclear",
        displayIcon = "❓",
        defaultHeadline = "PATH UNCLEAR  |  PLEASE STOP",
        isSafetyCritical = true
    ),

    // 10. GPS signal degraded, using estimated steps
    GPS_WEAK(
        stateName = "GPS Weak",
        displayIcon = "📡",
        defaultHeadline = "GPS SIGNAL WEAK"
    ),

    // 11. Network offline banner, on-device vision remains active
    OFFLINE(
        stateName = "Offline",
        displayIcon = "📶",
        defaultHeadline = "OFFLINE MODE ACTIVE"
    ),

    // 12. Camera sensor obscured or unavailable
    CAMERA_UNAVAILABLE(
        stateName = "Camera Unavailable",
        displayIcon = "📷",
        defaultHeadline = "CAMERA FEED UNAVAILABLE",
        isSafetyCritical = true
    ),

    // 13. Emergency SOS screen active
    GUARDIAN_EMERGENCY(
        stateName = "Emergency SOS",
        displayIcon = "🚨",
        defaultHeadline = "EMERGENCY ALERT",
        isSafetyCritical = true
    ),

    // 14. Settings and guardian configuration
    SETTINGS(
        stateName = "Settings",
        displayIcon = "⚙️",
        defaultHeadline = "SETTINGS & PREFERENCES"
    );

    val isNavigating: Boolean
        get() = this == NAV_PATH_CLEAR || this == NAV_APPROACHING_TURN || this == OBSTACLE_WARNING || this == CRITICAL_STOP || this == PATH_UNCLEAR
}
