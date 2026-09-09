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

    // --- Offline Areas ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOfflineArea(area: OfflineAreaEntity): Long

    @Query("SELECT * FROM offline_areas ORDER BY downloadTimestamp DESC")
    suspend fun getAllOfflineAreas(): List<OfflineAreaEntity>

    @Query("SELECT * FROM offline_areas WHERE LOWER(areaName) = LOWER(:name) LIMIT 1")
    suspend fun getOfflineAreaByName(name: String): OfflineAreaEntity?

    @Query("DELETE FROM offline_areas WHERE id = :id")
    suspend fun deleteOfflineArea(id: Long)

    @Query("DELETE FROM offline_areas WHERE LOWER(areaName) = LOWER(:name)")
    suspend fun deleteOfflineAreaByName(name: String)

    @Query("SELECT SUM(storageBytes) FROM offline_areas")
    suspend fun getTotalOfflineStorageUsedBytes(): Long?

    // --- Cached Routes ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCachedRoute(route: CachedRouteEntity): Long

    @Query("SELECT * FROM cached_routes WHERE LOWER(destinationName) = LOWER(:destinationName) ORDER BY savedTimestamp DESC LIMIT 1")
    suspend fun getCachedRoute(destinationName: String): CachedRouteEntity?

    @Query("SELECT * FROM cached_routes ORDER BY savedTimestamp DESC")
    suspend fun getCachedRoutes(): List<CachedRouteEntity>

    @Query("SELECT * FROM cached_routes ORDER BY savedTimestamp DESC LIMIT 1")
    suspend fun getLatestCachedRoute(): CachedRouteEntity?

    @Query("DELETE FROM cached_routes")
    suspend fun clearCachedRoutes()
}
