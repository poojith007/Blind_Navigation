package com.blindnav.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey

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
    val highContrastTheme: Boolean = true
)
