package com.blindnav.app.i18n

import android.graphics.RectF
import com.blindnav.app.ml.DetectedObject
import com.blindnav.app.ml.Position
import com.blindnav.app.ml.ThreatLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class MultilingualObstacleGuidanceTest {

    @Test
    fun testEnglishObstacleGuidance() {
        val stopVehicle = LocaleManager.getObstacleInstruction(
            position = Position.CENTER,
            threatLevel = ThreatLevel.DANGER,
            isVehicle = true,
            language = SupportedLanguage.ENGLISH
        )
        assertEquals("STOP. Vehicle ahead.", stopVehicle)

        val rightVehicle = LocaleManager.getObstacleInstruction(
            position = Position.RIGHT,
            threatLevel = ThreatLevel.DANGER,
            isVehicle = true,
            language = SupportedLanguage.ENGLISH
        )
        assertEquals("Vehicle approaching from your right. Stop.", rightVehicle)

        val stopGeneral = LocaleManager.getObstacleInstruction(
            position = Position.CENTER,
            threatLevel = ThreatLevel.DANGER,
            isVehicle = false,
            language = SupportedLanguage.ENGLISH
        )
        assertEquals("STOP. Obstacle ahead.", stopGeneral)

        val cautionCenterLeft = LocaleManager.getObstacleInstruction(
            position = Position.CENTER,
            threatLevel = ThreatLevel.CAUTION,
            isVehicle = false,
            evasiveDirection = Position.LEFT,
            language = SupportedLanguage.ENGLISH
        )
        assertEquals("Obstacle ahead. Move slightly left.", cautionCenterLeft)

        val cautionLeft = LocaleManager.getObstacleInstruction(
            position = Position.LEFT,
            threatLevel = ThreatLevel.CAUTION,
            isVehicle = false,
            language = SupportedLanguage.ENGLISH
        )
        assertEquals("Obstacle on your left. Keep right.", cautionLeft)
    }

    @Test
    fun testHindiObstacleGuidance() {
        val stopVehicle = LocaleManager.getObstacleInstruction(
            position = Position.CENTER,
            threatLevel = ThreatLevel.DANGER,
            isVehicle = true,
            language = SupportedLanguage.HINDI
        )
        assertEquals("रुकें! आगे वाहन है।", stopVehicle)

        val leftVehicle = LocaleManager.getObstacleInstruction(
            position = Position.LEFT,
            threatLevel = ThreatLevel.DANGER,
            isVehicle = true,
            language = SupportedLanguage.HINDI
        )
        assertEquals("आपके बाईं ओर से वाहन आ रहा है। रुकें।", leftVehicle)

        val stopGeneral = LocaleManager.getObstacleInstruction(
            position = Position.CENTER,
            threatLevel = ThreatLevel.DANGER,
            isVehicle = false,
            language = SupportedLanguage.HINDI
        )
        assertEquals("रुकें! आगे बाधा है।", stopGeneral)

        val cautionCenterRight = LocaleManager.getObstacleInstruction(
            position = Position.CENTER,
            threatLevel = ThreatLevel.CAUTION,
            isVehicle = false,
            evasiveDirection = Position.RIGHT,
            language = SupportedLanguage.HINDI
        )
        assertEquals("आगे बाधा है। थोड़ा दाएं चलें।", cautionCenterRight)

        val cautionRight = LocaleManager.getObstacleInstruction(
            position = Position.RIGHT,
            threatLevel = ThreatLevel.CAUTION,
            isVehicle = false,
            language = SupportedLanguage.HINDI
        )
        assertEquals("आपके दाईं ओर बाधा है। बाईं ओर रहें।", cautionRight)
    }

    @Test
    fun testKannadaObstacleGuidance() {
        val stopVehicle = LocaleManager.getObstacleInstruction(
            position = Position.CENTER,
            threatLevel = ThreatLevel.DANGER,
            isVehicle = true,
            language = SupportedLanguage.KANNADA
        )
        assertEquals("ನಿಲ್ಲಿಸಿ! ಮುಂದೆ ವಾಹನವಿದೆ.", stopVehicle)

        val rightVehicle = LocaleManager.getObstacleInstruction(
            position = Position.RIGHT,
            threatLevel = ThreatLevel.DANGER,
            isVehicle = true,
            language = SupportedLanguage.KANNADA
        )
        assertEquals("ನಿಮ್ಮ ಬಲಭಾಗದಿಂದ ವಾಹನ ಬರುತ್ತಿದೆ. ನಿಲ್ಲಿಸಿ.", rightVehicle)

        val stopGeneral = LocaleManager.getObstacleInstruction(
            position = Position.CENTER,
            threatLevel = ThreatLevel.DANGER,
            isVehicle = false,
            language = SupportedLanguage.KANNADA
        )
        assertEquals("ನಿಲ್ಲಿಸಿ! ಮುಂದೆ ಅಡಚಣೆ ಇದೆ.", stopGeneral)

        val cautionCenterLeft = LocaleManager.getObstacleInstruction(
            position = Position.CENTER,
            threatLevel = ThreatLevel.CAUTION,
            isVehicle = false,
            evasiveDirection = Position.LEFT,
            language = SupportedLanguage.KANNADA
        )
        assertEquals("ಮುಂದೆ ಅಡಚಣೆ ಇದೆ. ಸ್ವಲ್ಪ ಎಡಕ್ಕೆ ಸಾಗಿ.", cautionCenterLeft)

        val cautionLeft = LocaleManager.getObstacleInstruction(
            position = Position.LEFT,
            threatLevel = ThreatLevel.CAUTION,
            isVehicle = false,
            language = SupportedLanguage.KANNADA
        )
        assertEquals("ನಿಮ್ಮ ಎಡಭಾಗದಲ್ಲಿ ಅಡಚಣೆ ಇದೆ. ಬಲಕ್ಕೆ ಇರಿ.", cautionLeft)
    }

    @Test
    fun testDetectedObjectToActionableInstruction() {
        val carDanger = DetectedObject(
            classId = 2,
            label = "car",
            confidence = 0.95f,
            boundingBox = RectF(0.4f, 0.4f, 0.6f, 0.8f),
            distanceMeters = 1.0f,
            position = Position.CENTER,
            threatLevel = ThreatLevel.DANGER
        )

        assertEquals("STOP. Vehicle ahead.", carDanger.toActionableInstruction(language = SupportedLanguage.ENGLISH))
        assertEquals("रुकें! आगे वाहन है।", carDanger.toActionableInstruction(language = SupportedLanguage.HINDI))
        assertEquals("ನಿಲ್ಲಿಸಿ! ಮುಂದೆ ವಾಹನವಿದೆ.", carDanger.toActionableInstruction(language = SupportedLanguage.KANNADA))

        val personCaution = DetectedObject(
            classId = 0,
            label = "person",
            confidence = 0.85f,
            boundingBox = RectF(0.4f, 0.4f, 0.6f, 0.8f),
            distanceMeters = 2.0f,
            position = Position.CENTER,
            threatLevel = ThreatLevel.CAUTION
        )

        assertEquals("Obstacle ahead. Move slightly right.", personCaution.toActionableInstruction(evasiveDirection = Position.RIGHT, language = SupportedLanguage.ENGLISH))
        assertEquals("आगे बाधा है। थोड़ा बाएं चलें।", personCaution.toActionableInstruction(evasiveDirection = Position.LEFT, language = SupportedLanguage.HINDI))
        assertEquals("ಮುಂದೆ ಅಡಚಣೆ ಇದೆ. ಸ್ವಲ್ಪ ಎಡಕ್ಕೆ ಸಾಗಿ.", personCaution.toActionableInstruction(evasiveDirection = Position.LEFT, language = SupportedLanguage.KANNADA))
    }

    @Test
    fun testPathClearText_AllLanguages() {
        assertEquals("Path clear. Continue straight.", LocaleManager.getPathClearText(navActive = true, language = SupportedLanguage.ENGLISH))
        assertEquals("Continue straight.", LocaleManager.getPathClearText(navActive = false, language = SupportedLanguage.ENGLISH))

        assertEquals("रास्ता साफ है। सीधे चलते रहें।", LocaleManager.getPathClearText(navActive = true, language = SupportedLanguage.HINDI))
        assertEquals("सीधे चलते रहें।", LocaleManager.getPathClearText(navActive = false, language = SupportedLanguage.HINDI))

        assertEquals("ದಾರಿ ಸ್ಪಷ್ಟವಾಗಿದೆ. ನೇರವಾಗಿ ಮುಂದುವರಿಯಿರಿ.", LocaleManager.getPathClearText(navActive = true, language = SupportedLanguage.KANNADA))
        assertEquals("ನೇರವಾಗಿ ಸಾಗಿ.", LocaleManager.getPathClearText(navActive = false, language = SupportedLanguage.KANNADA))
    }

    @Test
    fun testSirenAlertText_AllLanguages() {
        assertEquals("Emergency siren detected nearby. Exercise caution.", LocaleManager.getSirenAlertText(SupportedLanguage.ENGLISH))
        assertEquals("आस-पास आपातकालीन सायरन सुनाई दिया है। सावधानी बरतें।", LocaleManager.getSirenAlertText(SupportedLanguage.HINDI))
        assertEquals("ಹತ್ತಿರದಲ್ಲಿ ತುರ್ತು ಸೈರನ್ ಪತ್ತೆಯಾಗಿದೆ. ಎಚ್ಚರದಿಂದಿರಿ.", LocaleManager.getSirenAlertText(SupportedLanguage.KANNADA))
    }

    @Test
    fun testHeadlineBanner_AllLanguages() {
        assertEquals("STOP. IMMEDIATE OBSTACLE", LocaleManager.getHeadlineBanner(isUrgent = true, language = SupportedLanguage.ENGLISH))
        assertEquals("CAUTION. OBSTACLE AHEAD", LocaleManager.getHeadlineBanner(isUrgent = false, language = SupportedLanguage.ENGLISH))

        assertEquals("रुकें! तत्काल बाधा", LocaleManager.getHeadlineBanner(isUrgent = true, language = SupportedLanguage.HINDI))
        assertEquals("सावधान! आगे बाधा है", LocaleManager.getHeadlineBanner(isUrgent = false, language = SupportedLanguage.HINDI))

        assertEquals("ನಿಲ್ಲಿಸಿ! ತಕ್ಷಣದ ಅಡಚಣೆ", LocaleManager.getHeadlineBanner(isUrgent = true, language = SupportedLanguage.KANNADA))
        assertEquals("ಎಚ್ಚರಿಕೆ! ಮುಂದೆ ಅಡಚಣೆ", LocaleManager.getHeadlineBanner(isUrgent = false, language = SupportedLanguage.KANNADA))
    }

    @Test
    fun testLanguageConfirmationSpoken_AllLanguages() {
        assertEquals("Language set to English.", LocaleManager.getLanguageConfirmationSpoken(SupportedLanguage.ENGLISH))
        assertEquals("भाषा हिंदी में सेट की गई है।", LocaleManager.getLanguageConfirmationSpoken(SupportedLanguage.HINDI))
        assertEquals("ಭಾಷೆಯನ್ನು ಕನ್ನಡಕ್ಕೆ ಹೊಂದಿಸಲಾಗಿದೆ.", LocaleManager.getLanguageConfirmationSpoken(SupportedLanguage.KANNADA))
    }
}
