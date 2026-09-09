package com.blindnav.app.location

import android.location.Location

enum class GpsConfidence(val label: String, val displayIcon: String) {
    GOOD("GPS GOOD", "🟢"),
    WEAK("GPS WEAK", "🟡"),
    UNAVAILABLE("GPS UNAVAILABLE", "🔴");

    companion object {
        fun evaluate(location: Location?): GpsConfidence {
            if (location == null) return UNAVAILABLE
            return when {
                !location.hasAccuracy() -> WEAK
                location.accuracy <= 15.0f -> GOOD
                location.accuracy <= 35.0f -> WEAK
                else -> UNAVAILABLE
            }
        }
    }
}
