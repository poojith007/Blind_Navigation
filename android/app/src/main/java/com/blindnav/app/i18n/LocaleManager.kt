package com.blindnav.app.i18n

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.blindnav.app.R
import com.blindnav.app.ml.Position
import com.blindnav.app.ml.ThreatLevel
import java.util.Locale

enum class SupportedLanguage(
    val code: String,
    val locale: Locale,
    val displayName: String,
    val spokenName: String
) {
    ENGLISH("en-IN", Locale("en", "IN"), "English", "English"),
    HINDI("hi-IN", Locale("hi", "IN"), "हिंदी (Hindi)", "हिंदी"),
    KANNADA("kn-IN", Locale("kn", "IN"), "ಕನ್ನಡ (Kannada)", "ಕನ್ನಡ");

    companion object {
        fun fromCode(code: String?): SupportedLanguage {
            if (code.isNullOrBlank()) return ENGLISH
            val normalized = code.trim().lowercase()
            return when {
                normalized.startsWith("hi") -> HINDI
                normalized.startsWith("kn") -> KANNADA
                else -> ENGLISH
            }
        }
    }
}

object LocaleManager {

    private const val TAG = "LocaleManager"

    @Volatile
    var currentLanguage: SupportedLanguage = SupportedLanguage.ENGLISH
        private set

    val currentLocale: Locale
        get() = currentLanguage.locale

    var onLanguageChanged: ((SupportedLanguage) -> Unit)? = null

    fun setLanguage(language: SupportedLanguage) {
        if (currentLanguage == language) return
        currentLanguage = language
        Locale.setDefault(language.locale)
        Log.i(TAG, "Language set to: ${language.code} (${language.displayName})")
        onLanguageChanged?.invoke(language)
    }

    fun setLanguageByCode(code: String?) {
        setLanguage(SupportedLanguage.fromCode(code))
    }

    /**
     * Creates a localized Context wrapping the specified language locale.
     */
    fun getLocalizedContext(context: Context, language: SupportedLanguage = currentLanguage): Context {
        return try {
            val config = Configuration(context.resources.configuration)
            config.setLocale(language.locale)
            context.createConfigurationContext(config)
        } catch (e: Exception) {
            context
        }
    }

    /**
     * Retrieves a localized string resource for the current language.
     * Safely falls back if context is unavailable.
     */
    fun getString(context: Context?, resId: Int, vararg formatArgs: Any): String {
        if (context == null) return ""
        return try {
            val localizedCtx = getLocalizedContext(context)
            if (formatArgs.isNotEmpty()) {
                localizedCtx.getString(resId, *formatArgs)
            } else {
                localizedCtx.getString(resId)
            }
        } catch (e: Exception) {
            try {
                if (formatArgs.isNotEmpty()) context.getString(resId, *formatArgs) else context.getString(resId)
            } catch (_: Exception) {
                ""
            }
        }
    }

    /**
     * Produces action-first obstacle guidance in the specified language (English, Hindi, or Kannada).
     * Guaranteed pure Kotlin — works in all environments and unit tests without Android Context.
     */
    fun getObstacleInstruction(
        position: Position,
        threatLevel: ThreatLevel,
        isVehicle: Boolean,
        evasiveDirection: Position = Position.RIGHT,
        distanceMeters: Float = 2.0f,
        language: SupportedLanguage = currentLanguage
    ): String {
        val isEmergency = threatLevel == ThreatLevel.DANGER || distanceMeters <= 1.2f

        return when (language) {
            SupportedLanguage.ENGLISH -> {
                if (isEmergency) {
                    if (isVehicle) {
                        when (position) {
                            Position.RIGHT -> "Vehicle approaching from your right. Stop."
                            Position.LEFT -> "Vehicle approaching from your left. Stop."
                            Position.CENTER -> "STOP. Vehicle ahead."
                        }
                    } else {
                        "STOP. Obstacle ahead."
                    }
                } else {
                    when (position) {
                        Position.CENTER -> {
                            if (evasiveDirection == Position.LEFT) {
                                "Obstacle ahead. Move slightly left."
                            } else {
                                "Obstacle ahead. Move slightly right."
                            }
                        }
                        Position.LEFT -> "Obstacle on your left. Keep right."
                        Position.RIGHT -> "Obstacle on your right. Keep left."
                    }
                }
            }
            SupportedLanguage.HINDI -> {
                if (isEmergency) {
                    if (isVehicle) {
                        when (position) {
                            Position.RIGHT -> "आपके दाईं ओर से वाहन आ रहा है। रुकें।"
                            Position.LEFT -> "आपके बाईं ओर से वाहन आ रहा है। रुकें।"
                            Position.CENTER -> "रुकें! आगे वाहन है।"
                        }
                    } else {
                        "रुकें! आगे बाधा है।"
                    }
                } else {
                    when (position) {
                        Position.CENTER -> {
                            if (evasiveDirection == Position.LEFT) {
                                "आगे बाधा है। थोड़ा बाएं चलें।"
                            } else {
                                "आगे बाधा है। थोड़ा दाएं चलें।"
                            }
                        }
                        Position.LEFT -> "आपके बाईं ओर बाधा है। दाईं ओर रहें।"
                        Position.RIGHT -> "आपके दाईं ओर बाधा है। बाईं ओर रहें।"
                    }
                }
            }
            SupportedLanguage.KANNADA -> {
                if (isEmergency) {
                    if (isVehicle) {
                        when (position) {
                            Position.RIGHT -> "ನಿಮ್ಮ ಬಲಭಾಗದಿಂದ ವಾಹನ ಬರುತ್ತಿದೆ. ನಿಲ್ಲಿಸಿ."
                            Position.LEFT -> "ನಿಮ್ಮ ಎಡಭಾಗದಿಂದ ವಾಹನ ಬರುತ್ತಿದೆ. ನಿಲ್ಲಿಸಿ."
                            Position.CENTER -> "ನಿಲ್ಲಿಸಿ! ಮುಂದೆ ವಾಹನವಿದೆ."
                        }
                    } else {
                        "ನಿಲ್ಲಿಸಿ! ಮುಂದೆ ಅಡಚಣೆ ಇದೆ."
                    }
                } else {
                    when (position) {
                        Position.CENTER -> {
                            if (evasiveDirection == Position.LEFT) {
                                "ಮುಂದೆ ಅಡಚಣೆ ಇದೆ. ಸ್ವಲ್ಪ ಎಡಕ್ಕೆ ಸಾಗಿ."
                            } else {
                                "ಮುಂದೆ ಅಡಚಣೆ ಇದೆ. ಸ್ವಲ್ಪ ಬಲಕ್ಕೆ ಸಾಗಿ."
                            }
                        }
                        Position.LEFT -> "ನಿಮ್ಮ ಎಡಭಾಗದಲ್ಲಿ ಅಡಚಣೆ ಇದೆ. ಬಲಕ್ಕೆ ಇರಿ."
                        Position.RIGHT -> "ನಿಮ್ಮ ಬಲಭಾಗದಲ್ಲಿ ಅಡಚಣೆ ಇದೆ. ಎಡಕ್ಕೆ ಇರಿ."
                    }
                }
            }
        }
    }

    /**
     * Localized clear path reassurance announcement.
     */
    fun getPathClearText(navActive: Boolean, language: SupportedLanguage = currentLanguage): String {
        return when (language) {
            SupportedLanguage.ENGLISH -> if (navActive) "Path clear. Continue straight." else "Continue straight."
            SupportedLanguage.HINDI -> if (navActive) "रास्ता साफ है। सीधे चलते रहें।" else "सीधे चलते रहें।"
            SupportedLanguage.KANNADA -> if (navActive) "ದಾರಿ ಸ್ಪಷ್ಟವಾಗಿದೆ. ನೇರವಾಗಿ ಮುಂದುವರಿಯಿರಿ." else "ನೇರವಾಗಿ ಸಾಗಿ."
        }
    }

    /**
     * Localized acoustic siren warning.
     */
    fun getSirenAlertText(language: SupportedLanguage = currentLanguage): String {
        return when (language) {
            SupportedLanguage.ENGLISH -> "Emergency siren detected nearby. Exercise caution."
            SupportedLanguage.HINDI -> "आस-पास आपातकालीन सायरन सुनाई दिया है। सावधानी बरतें।"
            SupportedLanguage.KANNADA -> "ಹತ್ತಿರದಲ್ಲಿ ತುರ್ತು ಸೈರನ್ ಪತ್ತೆಯಾಗಿದೆ. ಎಚ್ಚರದಿಂದಿರಿ."
        }
    }

    /**
     * Localized headline banner text.
     */
    fun getHeadlineBanner(isUrgent: Boolean, language: SupportedLanguage = currentLanguage): String {
        return when (language) {
            SupportedLanguage.ENGLISH -> if (isUrgent) "STOP. IMMEDIATE OBSTACLE" else "CAUTION. OBSTACLE AHEAD"
            SupportedLanguage.HINDI -> if (isUrgent) "रुकें! तत्काल बाधा" else "सावधान! आगे बाधा है"
            SupportedLanguage.KANNADA -> if (isUrgent) "ನಿಲ್ಲಿಸಿ! ತಕ್ಷಣದ ಅಡಚಣೆ" else "ಎಚ್ಚರಿಕೆ! ಮುಂದೆ ಅಡಚಣೆ"
        }
    }

    /**
     * Localized spoken confirmation when language is changed.
     */
    fun getLanguageConfirmationSpoken(language: SupportedLanguage): String {
        return when (language) {
            SupportedLanguage.ENGLISH -> "Language set to English."
            SupportedLanguage.HINDI -> "भाषा हिंदी में सेट की गई है।"
            SupportedLanguage.KANNADA -> "ಭಾಷೆಯನ್ನು ಕನ್ನಡಕ್ಕೆ ಹೊಂದಿಸಲಾಗಿದೆ."
        }
    }
}
