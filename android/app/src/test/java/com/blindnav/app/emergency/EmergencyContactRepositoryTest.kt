package com.blindnav.app.emergency

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyContactRepositoryTest {

    @Test
    fun testInitialStateHasNoGuardian() {
        val fakePrefs = FakeSharedPreferences()
        val repository = EmergencyContactRepository(fakePrefs)

        assertFalse(repository.hasGuardian())
        assertNull(repository.getPrimaryGuardian())
        assertNull(repository.getBackupGuardian())
    }

    @Test
    fun testSaveAndRetrievePrimaryGuardian() {
        val fakePrefs = FakeSharedPreferences()
        val repository = EmergencyContactRepository(fakePrefs)

        val guardian = GuardianContact("Dr. Ramesh", "+91 9876543210")
        repository.saveEmergencyConfig(primary = guardian)

        assertTrue(repository.hasGuardian())
        val retrieved = repository.getPrimaryGuardian()
        assertNotNull(retrieved)
        assertEquals("Dr. Ramesh", retrieved?.name)
        assertEquals("+91 9876543210", retrieved?.phoneNumber)
        assertFalse(retrieved?.isBackup ?: true)
        assertNull(repository.getBackupGuardian())
    }

    @Test
    fun testPersistenceAcrossAppRestart() {
        // Shared backing storage representing persistent on-device disk storage
        val sharedDiskStorage = FakeSharedPreferences()

        // 1. User configures guardian in Session 1
        val firstLaunchRepo = EmergencyContactRepository(sharedDiskStorage)
        val guardian = GuardianContact("Priya Sharma", "+91 98111 22334")
        val backup = GuardianContact("Amit Sharma", "+91 98222 33445", isBackup = true)
        firstLaunchRepo.saveEmergencyConfig(primary = guardian, backup = backup)

        // 2. App restarts (new repository instance created on fresh app launch)
        val secondLaunchRepo = EmergencyContactRepository(sharedDiskStorage)

        assertTrue(secondLaunchRepo.hasGuardian())
        val primary = secondLaunchRepo.getPrimaryGuardian()
        assertNotNull(primary)
        assertEquals("Priya Sharma", primary?.name)
        assertEquals("+91 98111 22334", primary?.phoneNumber)

        val retrievedBackup = secondLaunchRepo.getBackupGuardian()
        assertNotNull(retrievedBackup)
        assertEquals("Amit Sharma", retrievedBackup?.name)
        assertEquals("+91 98222 33445", retrievedBackup?.phoneNumber)
    }

    @Test
    fun testClearContacts() {
        val fakePrefs = FakeSharedPreferences()
        val repository = EmergencyContactRepository(fakePrefs)

        val guardian = GuardianContact("Caregiver", "9876543210")
        repository.saveEmergencyConfig(primary = guardian)
        assertTrue(repository.hasGuardian())

        repository.clear()
        assertFalse(repository.hasGuardian())
        assertNull(repository.getPrimaryGuardian())
        assertNull(repository.getBackupGuardian())
    }

    /**
     * In-memory mock of SharedPreferences for robust, rapid JVM unit testing.
     */
    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)

        override fun getString(key: String?, defValue: String?): String? {
            return (data[key] as? String) ?: defValue
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (data[key] as? MutableSet<String>) ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val backingMap: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val pendingChanges = mutableMapOf<String, Any?>()
            private var clearPending = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) pendingChanges[key] = value
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) pendingChanges[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pendingChanges[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pendingChanges[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pendingChanges[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pendingChanges[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) pendingChanges[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearPending = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearPending) {
                    backingMap.clear()
                    clearPending = false
                }
                for ((key, value) in pendingChanges) {
                    if (value == null) {
                        backingMap.remove(key)
                    } else {
                        backingMap[key] = value
                    }
                }
                pendingChanges.clear()
            }
        }
    }
}
