package com.blindnav.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Locale

@Entity(tableName = "detection_history")
data class DetectionLog(
    @PrimaryKey(autoGenerate = true) val logId: Long = 0,
    val objectLabel: String,
    val confidence: Float,
    val distanceMeters: Float,
    val threatLevel: String,
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_preferences")
data class UserPreferences(
    @PrimaryKey val userId: Int = 1,
    val speechRate: Float = 1.1f,
    val vibrationIntensity: Int = 2,
    val emergencyContactPhone: String = "",
    val guardianName: String = "",
    val backupGuardianName: String = "",
    val backupGuardianPhone: String = "",
    val language: String = "en-US",
    val highContrastTheme: Boolean = true,
    val homeAddress: String = "",
    val homeLat: Double = 0.0,
    val homeLng: Double = 0.0,
    val isVoiceGuidanceEnabled: Boolean = true,
    val isVibrationEnabled: Boolean = true
)

@Entity(tableName = "offline_areas")
data class OfflineAreaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val areaName: String,
    val coverageDescription: String,
    val storageBytes: Long,
    val isDownloaded: Boolean = true,
    val minLat: Double = 0.0,
    val maxLat: Double = 0.0,
    val minLng: Double = 0.0,
    val maxLng: Double = 0.0,
    val downloadTimestamp: Long = System.currentTimeMillis()
) {
    val formattedSize: String
        get() {
            val mb = storageBytes / (1024.0 * 1024.0)
            return if (mb >= 1.0) {
                String.format(Locale.US, "%.1f MB", mb)
            } else {
                val kb = storageBytes / 1024.0
                String.format(Locale.US, "%.0f KB", kb)
            }
        }
}

@Entity(tableName = "cached_routes")
data class CachedRouteEntity(
    @PrimaryKey(autoGenerate = true) val routeId: Long = 0,
    val destinationName: String,
    val destinationLat: Double,
    val destinationLng: Double,
    val routeJson: String,
    val totalDistanceMeters: Float,
    val savedTimestamp: Long = System.currentTimeMillis()
)
