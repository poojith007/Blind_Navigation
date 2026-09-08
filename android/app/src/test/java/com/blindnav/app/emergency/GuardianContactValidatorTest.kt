package com.blindnav.app.emergency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianContactValidatorTest {

    @Test
    fun testValidIndianMobileNumber() {
        val result = GuardianContactValidator.validate("Rajesh Kumar", "+91 98765 43210")
        assertTrue(result is ValidationResult.Success)
        val contact = (result as ValidationResult.Success).contact
        assertEquals("Rajesh Kumar", contact.name)
        assertEquals("+91 98765 43210", contact.phoneNumber)
        assertFalse(contact.isBackup)
    }

    @Test
    fun testValidUSNumber() {
        val result = GuardianContactValidator.validate("Jane Doe", "(555) 019-2834")
        assertTrue(result is ValidationResult.Success)
        val contact = (result as ValidationResult.Success).contact
        assertEquals("Jane Doe", contact.name)
        assertEquals("(555) 019-2834", contact.phoneNumber)
    }

    @Test
    fun testValidStandardDigitsOnly() {
        val result = GuardianContactValidator.validate("Caregiver", "9876543210")
        assertTrue(result is ValidationResult.Success)
        val contact = (result as ValidationResult.Success).contact
        assertEquals("9876543210", contact.phoneNumber)
    }

    @Test
    fun testRejectsHardcodedShortEmergencyNumbers() {
        // Hardcoded 911 must be rejected
        val result911 = GuardianContactValidator.validate("Emergency Services", "911")
        assertTrue(result911 is ValidationResult.Error)
        assertEquals("Phone number is too short. Please enter at least 7 digits.", (result911 as ValidationResult.Error).reason)

        // Hardcoded 112 must be rejected
        val result112 = GuardianContactValidator.validate("Police", "112")
        assertTrue(result112 is ValidationResult.Error)

        // Hardcoded 108 must be rejected
        val result108 = GuardianContactValidator.validate("Ambulance", "108")
        assertTrue(result108 is ValidationResult.Error)
    }

    @Test
    fun testRejectsEmptyOrBlankFields() {
        val emptyName = GuardianContactValidator.validate("", "9876543210")
        assertTrue(emptyName is ValidationResult.Error)
        assertEquals("Contact name cannot be empty.", (emptyName as ValidationResult.Error).reason)

        val blankName = GuardianContactValidator.validate("   ", "9876543210")
        assertTrue(blankName is ValidationResult.Error)

        val emptyPhone = GuardianContactValidator.validate("Guardian", "")
        assertTrue(emptyPhone is ValidationResult.Error)
        assertEquals("Phone number cannot be empty.", (emptyPhone as ValidationResult.Error).reason)

        val blankPhone = GuardianContactValidator.validate("Guardian", "   ")
        assertTrue(blankPhone is ValidationResult.Error)
    }

    @Test
    fun testRejectsInvalidCharactersInPhone() {
        val lettersPhone = GuardianContactValidator.validate("Guardian", "98765CALLME")
        assertTrue(lettersPhone is ValidationResult.Error)
        assertEquals("Phone number contains invalid characters.", (lettersPhone as ValidationResult.Error).reason)

        val symbolsPhone = GuardianContactValidator.validate("Guardian", "98765@#$$%")
        assertTrue(symbolsPhone is ValidationResult.Error)
    }

    @Test
    fun testRejectsExcessivelyLongPhone() {
        val longPhone = GuardianContactValidator.validate("Guardian", "1234567890123456")
        assertTrue(longPhone is ValidationResult.Error)
        assertEquals("Phone number cannot exceed 15 digits.", (longPhone as ValidationResult.Error).reason)
    }

    @Test
    fun testOptionalBackupValidation() {
        // Both blank -> treated as optional (null returned)
        val omittedBackup = GuardianContactValidator.validateOptionalBackup("", "")
        assertNull(omittedBackup)

        val whitespaceBackup = GuardianContactValidator.validateOptionalBackup("   ", "   ")
        assertNull(whitespaceBackup)

        // Valid backup contact
        val validBackup = GuardianContactValidator.validateOptionalBackup("Doctor Sharma", "+91 91234 56789")
        assertTrue(validBackup is ValidationResult.Success)
        val contact = (validBackup as ValidationResult.Success).contact
        assertEquals("Doctor Sharma", contact.name)
        assertEquals("+91 91234 56789", contact.phoneNumber)
        assertTrue(contact.isBackup)

        // Invalid backup: name without phone
        val missingPhone = GuardianContactValidator.validateOptionalBackup("Doctor Sharma", "")
        assertTrue(missingPhone is ValidationResult.Error)

        // Invalid backup: phone without name
        val missingName = GuardianContactValidator.validateOptionalBackup("", "9123456789")
        assertTrue(missingName is ValidationResult.Error)
    }

    @Test
    fun testEmergencyConfigHasPrimaryGuardian() {
        val emptyConfig = EmergencyConfig(primaryGuardian = null)
        assertFalse(emptyConfig.hasPrimaryGuardian)

        val validPrimary = GuardianContact("Guardian", "+91 9876543210")
        val configuredConfig = EmergencyConfig(primaryGuardian = validPrimary)
        assertTrue(configuredConfig.hasPrimaryGuardian)
    }
}
