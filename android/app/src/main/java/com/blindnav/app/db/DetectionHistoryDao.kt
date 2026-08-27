package com.blindnav.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DetectionHistoryDao {

    @Insert
    suspend fun insertLog(log: DetectionLog)

    @Query("SELECT * FROM detection_history ORDER BY timestampMs DESC LIMIT 50")
    suspend fun getRecentLogs(): List<DetectionLog>

    @Query("DELETE FROM detection_history")
    suspend fun clearHistory()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePreferences(prefs: UserPreferences)

    @Query("SELECT * FROM user_preferences WHERE userId = 1 LIMIT 1")
    suspend fun getPreferences(): UserPreferences?
}
