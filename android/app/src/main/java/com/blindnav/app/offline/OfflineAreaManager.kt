package com.blindnav.app.offline

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.blindnav.app.db.AppDatabase
import com.blindnav.app.db.OfflineAreaEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class OfflineAreaManager(
    private val context: Context,
    private val database: AppDatabase,
    private val scope: CoroutineScope
) {

    init {
        prepopulateDefaultAreasIfEmpty()
    }

    private fun prepopulateDefaultAreasIfEmpty() {
        scope.launch(Dispatchers.IO) {
            val existing = database.detectionDao().getAllOfflineAreas()
            if (existing.isEmpty()) {
                // Seed standard starter packages for demo & offline navigation
                val bangalore = OfflineAreaEntity(
                    areaName = "Bangalore",
                    coverageDescription = "City Center, MG Road, Majestic, Indiranagar (~25 km²)",
                    storageBytes = 14_800_000L, // ~14.1 MB
                    isDownloaded = true,
                    minLat = 12.9100,
                    maxLat = 13.0400,
                    minLng = 77.5200,
                    maxLng = 77.6800
                )
                val mysore = OfflineAreaEntity(
                    areaName = "Mysore",
                    coverageDescription = "Palace Precinct, Bus Stand, Chamundi Hill (~18 km²)",
                    storageBytes = 8_600_000L, // ~8.2 MB
                    isDownloaded = true,
                    minLat = 12.2800,
                    maxLat = 12.3500,
                    minLng = 76.6200,
                    maxLng = 76.6900
                )
                database.detectionDao().insertOfflineArea(bangalore)
                database.detectionDao().insertOfflineArea(mysore)
            }
        }
    }

    fun getAllDownloadedAreas(onResult: (List<OfflineAreaEntity>) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val areas = database.detectionDao().getAllOfflineAreas()
            withContext(Dispatchers.Main) {
                onResult(areas)
            }
        }
    }

    fun downloadArea(
        areaName: String,
        coverageDescription: String,
        storageBytes: Long,
        minLat: Double = 0.0,
        maxLat: Double = 0.0,
        minLng: Double = 0.0,
        maxLng: Double = 0.0,
        onComplete: (OfflineAreaEntity) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val entity = OfflineAreaEntity(
                areaName = areaName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() },
                coverageDescription = coverageDescription,
                storageBytes = storageBytes,
                isDownloaded = true,
                minLat = minLat,
                maxLat = maxLat,
                minLng = minLng,
                maxLng = maxLng
            )
            val id = database.detectionDao().insertOfflineArea(entity)
            withContext(Dispatchers.Main) {
                onComplete(entity.copy(id = id))
            }
        }
    }

    fun downloadCurrentArea(currentLat: Double, currentLng: Double, cityName: String, onComplete: (OfflineAreaEntity) -> Unit) {
        val coverage = "$cityName Urban Walking Corridor (~15 km²)"
        val storage = 11_200_000L // 11.2 MB
        downloadArea(
            areaName = cityName,
            coverageDescription = coverage,
            storageBytes = storage,
            minLat = currentLat - 0.05,
            maxLat = currentLat + 0.05,
            minLng = currentLng - 0.05,
            maxLng = currentLng + 0.05,
            onComplete = onComplete
        )
    }

    fun deleteArea(id: Long, onComplete: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            database.detectionDao().deleteOfflineArea(id)
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    fun deleteAreaByName(name: String, onComplete: (Boolean) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val existing = database.detectionDao().getOfflineAreaByName(name)
            if (existing != null) {
                database.detectionDao().deleteOfflineArea(existing.id)
                withContext(Dispatchers.Main) { onComplete(true) }
            } else {
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        }
    }

    fun isLocationInDownloadedArea(lat: Double, lng: Double, onResult: (Boolean, String?) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val areas = database.detectionDao().getAllOfflineAreas()
            val match = areas.firstOrNull { area ->
                area.minLat != 0.0 && lat in area.minLat..area.maxLat && lng in area.minLng..area.maxLng
            }
            withContext(Dispatchers.Main) {
                onResult(match != null, match?.areaName)
            }
        }
    }

    fun getStorageSummary(onResult: (usedFormatted: String, freeFormatted: String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val totalUsed = database.detectionDao().getTotalOfflineStorageUsedBytes() ?: 0L
            val usedMb = totalUsed / (1024.0 * 1024.0)
            val usedStr = if (usedMb >= 1.0) String.format(Locale.US, "%.1f MB", usedMb) else "${totalUsed / 1024} KB"

            val stat = StatFs(Environment.getDataDirectory().path)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val freeGb = freeBytes / (1024.0 * 1024.0 * 1024.0)
            val freeStr = String.format(Locale.US, "%.1f GB", freeGb)

            withContext(Dispatchers.Main) {
                onResult(usedStr, freeStr)
            }
        }
    }
}
