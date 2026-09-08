package com.blindnav.app.emergency

import android.content.Context
import android.content.SharedPreferences
import com.blindnav.app.db.AppDatabase
import com.blindnav.app.db.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EmergencyContactRepository(
    private val sharedPreferences: SharedPreferences,
    private val database: AppDatabase? = null,
    private val coroutineScope: CoroutineScope? = null
) {

    constructor(context: Context, database: AppDatabase? = null, coroutineScope: CoroutineScope? = null) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        database,
        coroutineScope
    )

    companion object {
        const val PREFS_NAME = "blind_nav_emergency_contacts"
        const val KEY_PRIMARY_NAME = "primary_guardian_name"
        const val KEY_PRIMARY_PHONE = "primary_guardian_phone"
        const val KEY_BACKUP_NAME = "backup_guardian_name"
        const val KEY_BACKUP_PHONE = "backup_guardian_phone"
    }

    /**
     * Synchronously retrieves the current emergency contacts configuration.
     * Guaranteed to be available immediately upon app launch.
     */
    fun getEmergencyConfig(): EmergencyConfig {
        val primaryName = sharedPreferences.getString(KEY_PRIMARY_NAME, "")?.trim().orEmpty()
        val primaryPhone = sharedPreferences.getString(KEY_PRIMARY_PHONE, "")?.trim().orEmpty()
        val backupName = sharedPreferences.getString(KEY_BACKUP_NAME, "")?.trim().orEmpty()
        val backupPhone = sharedPreferences.getString(KEY_BACKUP_PHONE, "")?.trim().orEmpty()

        val primary = if (primaryName.isNotEmpty() && primaryPhone.isNotEmpty()) {
            GuardianContact(primaryName, primaryPhone, isBackup = false)
        } else null

        val backup = if (backupName.isNotEmpty() && backupPhone.isNotEmpty()) {
            GuardianContact(backupName, backupPhone, isBackup = true)
        } else null

        return EmergencyConfig(primaryGuardian = primary, backupGuardian = backup)
    }

    fun hasGuardian(): Boolean {
        return getEmergencyConfig().hasPrimaryGuardian
    }

    fun getPrimaryGuardian(): GuardianContact? {
        return getEmergencyConfig().primaryGuardian
    }

    fun getBackupGuardian(): GuardianContact? {
        return getEmergencyConfig().backupGuardian
    }

    /**
     * Saves primary guardian and optional backup guardian.
     * Persists synchronously to SharedPreferences and asynchronously syncs to Room DB.
     */
    fun saveEmergencyConfig(
        primary: GuardianContact,
        backup: GuardianContact? = null
    ) {
        sharedPreferences.edit()
            .putString(KEY_PRIMARY_NAME, primary.name.trim())
            .putString(KEY_PRIMARY_PHONE, primary.phoneNumber.trim())
            .putString(KEY_BACKUP_NAME, backup?.name?.trim().orEmpty())
            .putString(KEY_BACKUP_PHONE, backup?.phoneNumber?.trim().orEmpty())
            .apply()

        syncToDatabase(primary, backup)
    }

    fun clear() {
        sharedPreferences.edit().clear().apply()
        coroutineScope?.launch(Dispatchers.IO) {
            database?.let { db ->
                val current = db.detectionDao().getPreferences() ?: UserPreferences()
                db.detectionDao().savePreferences(
                    current.copy(
                        emergencyContactPhone = "",
                        guardianName = "",
                        backupGuardianName = "",
                        backupGuardianPhone = ""
                    )
                )
            }
        }
    }

    private fun syncToDatabase(primary: GuardianContact, backup: GuardianContact?) {
        coroutineScope?.launch(Dispatchers.IO) {
            database?.let { db ->
                val current = db.detectionDao().getPreferences() ?: UserPreferences()
                db.detectionDao().savePreferences(
                    current.copy(
                        emergencyContactPhone = primary.phoneNumber,
                        guardianName = primary.name,
                        backupGuardianName = backup?.name.orEmpty(),
                        backupGuardianPhone = backup?.phoneNumber.orEmpty()
                    )
                )
            }
        }
    }
}
