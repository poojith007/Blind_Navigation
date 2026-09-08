package com.blindnav.app.emergency

data class GuardianContact(
    val name: String,
    val phoneNumber: String,
    val isBackup: Boolean = false
) {
    val displayLabel: String
        get() = if (name.isNotBlank()) "$name ($phoneNumber)" else phoneNumber
}

data class EmergencyConfig(
    val primaryGuardian: GuardianContact?,
    val backupGuardian: GuardianContact? = null
) {
    val hasPrimaryGuardian: Boolean
        get() = primaryGuardian != null && primaryGuardian.name.isNotBlank() && primaryGuardian.phoneNumber.isNotBlank()
}

sealed class ValidationResult {
    data class Success(val contact: GuardianContact) : ValidationResult()
    data class Error(val reason: String) : ValidationResult()
}

object GuardianContactValidator {

    private val PHONE_PATTERN = Regex("""^\+?[0-9\s\-()]+$""")

    /**
     * Validates a guardian contact.
     * Name must be non-empty.
     * Phone number must contain between 7 and 15 digits, and must only contain valid phone characters.
     * Hardcoded short codes like 911, 112, etc. (under 7 digits) are rejected.
     */
    fun validate(name: String?, phone: String?, isBackup: Boolean = false): ValidationResult {
        val trimmedName = name?.trim().orEmpty()
        val trimmedPhone = phone?.trim().orEmpty()

        if (trimmedName.isEmpty()) {
            return ValidationResult.Error("Contact name cannot be empty.")
        }

        if (trimmedPhone.isEmpty()) {
            return ValidationResult.Error("Phone number cannot be empty.")
        }

        if (!PHONE_PATTERN.matches(trimmedPhone)) {
            return ValidationResult.Error("Phone number contains invalid characters.")
        }

        val digitsOnly = trimmedPhone.filter { it.isDigit() }
        if (digitsOnly.length < 7) {
            return ValidationResult.Error("Phone number is too short. Please enter at least 7 digits.")
        }
        if (digitsOnly.length > 15) {
            return ValidationResult.Error("Phone number cannot exceed 15 digits.")
        }

        return ValidationResult.Success(
            GuardianContact(
                name = trimmedName,
                phoneNumber = trimmedPhone,
                isBackup = isBackup
            )
        )
    }

    /**
     * Validates an optional backup contact.
     * Returns null if both fields are blank (user chose not to configure a backup).
     * If either field is filled, both must be valid.
     */
    fun validateOptionalBackup(name: String?, phone: String?): ValidationResult? {
        val trimmedName = name?.trim().orEmpty()
        val trimmedPhone = phone?.trim().orEmpty()

        if (trimmedName.isEmpty() && trimmedPhone.isEmpty()) {
            return null
        }

        return validate(trimmedName, trimmedPhone, isBackup = true)
    }
}
