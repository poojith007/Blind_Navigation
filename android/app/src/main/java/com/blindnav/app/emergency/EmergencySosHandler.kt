package com.blindnav.app.emergency

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.blindnav.app.engine.IFeedbackEngine
import com.blindnav.app.location.LocationHelper

class EmergencySosHandler(
    private val context: Context,
    private val feedbackEngine: IFeedbackEngine,
    private val locationHelper: LocationHelper
) {

    companion object {
        /**
         * Formats the emergency distress message.
         * If location/mapsUrl is available, includes it.
         * If unavailable, explicitly states location could not be obtained without fabricating any data.
         */
        fun formatEmergencyMessage(
            location: Location?,
            address: String?,
            mapsUrl: String?,
            language: com.blindnav.app.i18n.SupportedLanguage = com.blindnav.app.i18n.LocaleManager.currentLanguage
        ): String {
            val locationDetail = when {
                !address.isNullOrBlank() && !mapsUrl.isNullOrBlank() -> "$address ($mapsUrl)"
                !mapsUrl.isNullOrBlank() -> mapsUrl
                location != null -> "Lat: ${location.latitude}, Lng: ${location.longitude}"
                else -> null
            }

            return when (language) {
                com.blindnav.app.i18n.SupportedLanguage.ENGLISH -> {
                    if (locationDetail != null) {
                        "Emergency alert from Blind Navigation. I may need assistance. My current location: $locationDetail."
                    } else {
                        "Emergency alert from Blind Navigation. I may need assistance. My current location could not be obtained."
                    }
                }
                com.blindnav.app.i18n.SupportedLanguage.HINDI -> {
                    if (locationDetail != null) {
                        "ब्लाइंड नेविगेशन से आपातकालीन अलर्ट। मुझे सहायता की आवश्यकता हो सकती है। मेरा वर्तमान स्थान: $locationDetail।"
                    } else {
                        "ब्लाइंड नेविगेशन से आपातकालीन अलर्ट। मुझे सहायता की आवश्यकता हो सकती है। मेरा वर्तमान स्थान प्राप्त नहीं हो सका।"
                    }
                }
                com.blindnav.app.i18n.SupportedLanguage.KANNADA -> {
                    if (locationDetail != null) {
                        "ಬ್ಲೈಂಡ್ ನೆವಿಗೇಷನ್‌ನಿಂದ ತುರ್ತು ಸಂದೇಶ. ನನಗೆ ಸಹಾಯ ಬೇಕಾಗಬಹುದು. ನನ್ನ ಪ್ರಸ್ತುತ ಸ್ಥಳ: $locationDetail."
                    } else {
                        "ಬ್ಲೈಂಡ್ ನೆವಿಗೇಷನ್‌ನಿಂದ ತುರ್ತು ಸಂದೇಶ. ನನಗೆ ಸಹಾಯ ಬೇಕಾಗಬಹುದು. ನನ್ನ ಪ್ರಸ್ತುತ ಸ್ಥಳ ಲಭ್ಯವಾಗಿಲ್ಲ."
                    }
                }
            }
        }
    }

    /**
     * Calls the configured guardian/trusted contact.
     * Uses ACTION_CALL if CALL_PHONE permission is granted, otherwise gracefully falls back to ACTION_DIAL.
     */
    fun callGuardian(contact: GuardianContact): Boolean {
        if (contact.phoneNumber.isBlank()) {
            val noPhoneMsg = when (com.blindnav.app.i18n.LocaleManager.currentLanguage) {
                com.blindnav.app.i18n.SupportedLanguage.ENGLISH -> "Cannot call guardian. No phone number provided."
                com.blindnav.app.i18n.SupportedLanguage.HINDI -> "अभिभावक को कॉल नहीं किया जा सकता। कोई फ़ोन नंबर नहीं दिया गया है।"
                com.blindnav.app.i18n.SupportedLanguage.KANNADA -> "ಪೋಷಕರಿಗೆ ಕರೆ ಮಾಡಲು ಸಾಧ್ಯವಿಲ್ಲ. ಯಾವುದೇ ಫೋನ್ ಸಂಖ್ಯೆ ನೀಡಿಲ್ಲ."
            }
            feedbackEngine.speakUrgent(noPhoneMsg)
            return false
        }

        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val callingMsg = when (com.blindnav.app.i18n.LocaleManager.currentLanguage) {
            com.blindnav.app.i18n.SupportedLanguage.ENGLISH -> "Calling Guardian ${contact.name}..."
            com.blindnav.app.i18n.SupportedLanguage.HINDI -> "अभिभावक ${contact.name} को कॉल किया जा रहा है..."
            com.blindnav.app.i18n.SupportedLanguage.KANNADA -> "ಪೋಷಕ ${contact.name} ಅವರಿಗೆ ಕರೆ ಮಾಡಲಾಗುತ್ತಿದೆ..."
        }
        feedbackEngine.speakUrgent(callingMsg)

        return try {
            val intent = if (hasCallPermission) {
                Intent(Intent.ACTION_CALL, Uri.parse("tel:${contact.phoneNumber}"))
            } else {
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.phoneNumber}"))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                val fallbackIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.phoneNumber}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                true
            } catch (ex: Exception) {
                ex.printStackTrace()
                feedbackEngine.speakUrgent("Failed to launch phone dialer: ${ex.localizedMessage ?: "Unknown error"}")
                false
            }
        }
    }

    /**
     * Fetches current GPS location and sends the emergency message to the guardian.
     */
    fun sendLocationAlert(
        contact: GuardianContact,
        backupContact: GuardianContact? = null,
        onComplete: (success: Boolean, message: String) -> Unit = { _, _ -> }
    ) {
        feedbackEngine.speakUrgent("Acquiring location to send emergency alert...")

        locationHelper.getCurrentLocation { location, address, mapsUrl ->
            val distressMessage = formatEmergencyMessage(location, address, mapsUrl)
            val success = sendSms(contact.phoneNumber, distressMessage)

            // Also send to backup contact if configured
            if (backupContact != null && backupContact.phoneNumber.isNotBlank()) {
                sendSms(backupContact.phoneNumber, distressMessage)
            }

            if (success) {
                feedbackEngine.speakUrgent("Emergency alert sent to Guardian ${contact.name}!")
                feedbackEngine.vibrateDanger()
                onComplete(true, distressMessage)
            } else {
                feedbackEngine.speakUrgent("Failed to send emergency SMS directly. Launching messaging app.")
                openSmsAppFallback(contact.phoneNumber, distressMessage)
                onComplete(false, distressMessage)
            }
        }
    }

    private fun sendSms(phoneNumber: String, message: String): Boolean {
        val hasSmsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasSmsPermission) {
            return false
        }

        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun openSmsAppFallback(phoneNumber: String, message: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phoneNumber")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
