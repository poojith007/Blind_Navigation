package com.blindnav.app.voice

import com.blindnav.app.i18n.SupportedLanguage
import java.util.regex.Pattern

sealed class VoiceIntent {
    // Navigation
    data class NavigateTo(val destination: String) : VoiceIntent()
    data object StartNavigation : VoiceIntent()
    data object StopNavigation : VoiceIntent()
    data object PauseNavigation : VoiceIntent()
    data object ResumeNavigation : VoiceIntent()
    data object RepeatInstruction : VoiceIntent()
    data object RepeatLast : VoiceIntent()
    data object NextInstruction : VoiceIntent()
    data object WhereAmI : VoiceIntent()
    data object LocationInquiry : VoiceIntent()
    data object HowFar : VoiceIntent()
    data object RemainingDistance : VoiceIntent()
    data object EstimatedArrival : VoiceIntent()
    data object GoHome : VoiceIntent()

    // Safety
    data object StopSafety : VoiceIntent()
    data object EmergencySos : VoiceIntent()
    data object CallGuardian : VoiceIntent()
    data object SendLocationAlert : VoiceIntent()
    data object CancelEmergency : VoiceIntent()

    // Settings
    data class ChangeLanguage(val targetLanguage: SupportedLanguage) : VoiceIntent()
    data class ToggleVoiceGuidance(val enable: Boolean) : VoiceIntent()
    data class AdjustVolume(val increase: Boolean) : VoiceIntent()
    data class ToggleVibration(val enable: Boolean) : VoiceIntent()
    data class AdjustSpeed(val faster: Boolean) : VoiceIntent()
    data class ToggleTorch(val enable: Boolean) : VoiceIntent()
    data class ToggleDetection(val pause: Boolean) : VoiceIntent()
    data object ToggleMap : VoiceIntent()
    data class SetEmergencyContact(val phoneNumber: String) : VoiceIntent()

    // Offline
    data object DownloadCurrentArea : VoiceIntent()
    data class DownloadArea(val areaName: String) : VoiceIntent()
    data object ShowOfflineMaps : VoiceIntent()
    data class DeleteOfflineArea(val areaName: String) : VoiceIntent()
    data object StorageInquiry : VoiceIntent()

    data object ToggleDemoMode : VoiceIntent()
    data object OpenGuardianSettings : VoiceIntent()

    // General & Diagnostics
    data object HistoryInquiry : VoiceIntent()
    data object ObstacleInquiry : VoiceIntent()
    data object OpenMaps : VoiceIntent()
    data object StatusInquiry : VoiceIntent()
    data object Help : VoiceIntent()
    data class Unknown(val rawText: String) : VoiceIntent()
}

object VoiceCommandParser {

    private val NAVIGATE_PATTERN = Pattern.compile(
        "^(?:navigate\\s+to|take\\s+me\\s+to|i\\s+want\\s+to\\s+go\\s+to|directions\\s+to|walk\\s+to|go\\s+to|head\\s+to)\\s+(.+)$",
        Pattern.CASE_INSENSITIVE
    )

    private val HINDI_NAV_PREFIX = Pattern.compile(
        "^(?:नेविगेट\\s+करें|नेविगेट\\s+टू|नेविगेट\\s+करो|ले\\s+चलो|रास्ता\\s+दिखाओ|की\\s+तरफ\\s+चलो|जाना\\s+है|navigate\\s+karo)\\s+(.+)$",
        Pattern.CASE_INSENSITIVE
    )

    private val HINDI_NAV_SUFFIX = Pattern.compile(
        "^(.+?)\\s+(?:चलो|जाओ|ले\\s+चलो|का\\s+रास्ता|जाना\\s+है|jao|chalo|ka\\s+rasta|jana\\s+hai)$",
        Pattern.CASE_INSENSITIVE
    )

    private val KANNADA_NAV_PREFIX = Pattern.compile(
        "^(?:ನೆವಿಗೇಟ್\\s+ಮಾಡಿ|ಕರೆದೊಯ್ಯಿರಿ|ದಾರಿ\\s+ತೋರಿಸಿ)\\s+(.+)$",
        Pattern.CASE_INSENSITIVE
    )

    private val KANNADA_NAV_SUFFIX = Pattern.compile(
        "^(.+?)(?:ಗೆ|ಕ್ಕೆ|\\s+ಗೆ|\\s+ಕ್ಕೆ)?\\s*(?:ಹೋಗಿ|ಹೋಗು|ದಾರಿ|ಕರೆದುಕೊಂಡು\\s+ಹೋಗು|ಕರೆದೊಯ್ಯಿರಿ|ನಡೆಯಿರಿ|ge\\s+hogu|ge\\s+dari|hogu|hogi)$",
        Pattern.CASE_INSENSITIVE
    )

    private val DOWNLOAD_AREA_PATTERN = Pattern.compile(
        "^(?:download\\s+area|download|save\\s+area)\\s+(.+)$",
        Pattern.CASE_INSENSITIVE
    )

    private val DELETE_AREA_PATTERN = Pattern.compile(
        "^(?:delete\\s+downloaded\\s+area|delete\\s+offline\\s+map|delete\\s+area|remove\\s+offline\\s+area)\\s*(.*)$",
        Pattern.CASE_INSENSITIVE
    )

    fun parse(rawText: String): VoiceIntent {
        val command = rawText.lowercase().trim()

        // 0. Language Switching Commands
        val isLangChange = command.contains("language") || command.contains("change") || command.contains("switch") ||
            command.contains("set") || command.contains("speak") || command.contains("talk") ||
            command.contains("भाषा") || command.contains("bhasha") || command.contains("ಭಾಷೆ") || command.contains("bhashe") ||
            command.contains("बोलो") || command.contains("bolo") || command.contains("बात") ||
            command.contains("ಮಾತನಾಡಿ") || command.contains("mathanadi") || command.contains("ಹೇಳಿ") || command.contains("heli") ||
            command.contains("में") || command.contains("me") || command.contains("mein") ||
            command.contains("ನಲ್ಲಿ") || command.contains("dalli") || command.contains("nalli")

        if (command.contains("hindi") || command.contains("हिंदी") || command.contains("हन्दी") || command.contains("ಹಿಂದಿ")) {
            if (isLangChange || command == "hindi" || command == "हिंदी" || command == "ಹಿಂದಿ") {
                return VoiceIntent.ChangeLanguage(SupportedLanguage.HINDI)
            }
        }
        if (command.contains("kannada") || command.contains("ಕನ್ನಡ") || command.contains("कन्नड़") || command.contains("कन्नड") || command.contains("kannad")) {
            if (isLangChange || command == "kannada" || command == "ಕನ್ನಡ" || command == "कन्नड़" || command == "kannad") {
                return VoiceIntent.ChangeLanguage(SupportedLanguage.KANNADA)
            }
        }
        if (command.contains("english") || command.contains("अंग्रेजी") || command.contains("अंग्रेज़ी") || command.contains("ಇಂಗ್ಲಿಷ್") || command.contains("angrezi") || command.contains("angreji")) {
            if (isLangChange || command == "english" || command == "अंग्रेजी" || command == "अंग्रेज़ी" || command == "ಇಂಗ್ಲಿಷ್") {
                return VoiceIntent.ChangeLanguage(SupportedLanguage.ENGLISH)
            }
        }

        // 1. Contact Setup & Guardian Settings
        if (command.contains("guardian settings") || command.contains("configure guardian") || command.contains("emergency settings") || command.contains("guardian setup") || command.contains("setup guardian")) {
            return VoiceIntent.OpenGuardianSettings
        }
        if (command.startsWith("set contact") || command.contains("emergency contact") || command.contains("set phone") || command.contains("update contact")) {
            val digits = command.filter { it.isDigit() }
            return if (digits.length >= 3) {
                VoiceIntent.SetEmergencyContact(digits)
            } else {
                VoiceIntent.OpenGuardianSettings
            }
        }

        // 2. Navigation Stop & Cancel Commands
        if (command.contains("stop navigation") || command.contains("cancel navigation") || command.contains("end route") || command.contains("stop route") || command.contains("stop walking") || command.contains("end navigation") ||
            command.contains("नेविगेशन बंद") || command.contains("नेविगेशन रोको") || command.contains("रूट बंद") || command.contains("चलना बंद") || command.contains("navigation band") ||
            command.contains("ನ್ಯಾವಿಗೇಷನ್ ನಿಲ್ಲಿಸಿ") || command.contains("ದಾರಿಯನ್ನು ರದ್ದುಗೊಳಿಸಿ") || command.contains("ನಡಿಗೆ ನಿಲ್ಲಿಸಿ") || command.contains("ನ್ಯಾವಿಗೇಷನ್ ರದ್ದು") || command.contains("ಮಾರ್ಗದರ್ಶನ ನಿಲ್ಲಿಸಿ") || command.contains("navigation nillisi")) {
            return VoiceIntent.StopNavigation
        }

        // 3. Critical Emergency & Safety
        if (command == "stop" || command == "halt" || command == "freeze" ||
            command == "रुकें" || command == "रुको" || command == "थमो" || command == "रुकिए" || command == "ruko" || command == "rukye" ||
            command == "ನಿಲ್ಲು" || command == "ನಿಲ್ಲಿಸಿ" || command == "nillu" || command == "nillisi") {
            return VoiceIntent.StopSafety
        }
        if (command.contains("cancel") || command.contains("abort") || command.contains("i am ok") || command.contains("false alarm") ||
            command.contains("रद्द करें") || command.contains("कैंसिल") || command.contains("रद्द करो") || command.contains("मैं ठीक हूँ") || command.contains("radd karo") || command.contains("theek hun") || command.contains("thik hu") ||
            command.contains("ರದ್ದುಮಾಡಿ") || command.contains("ರದ್ದು ಮಾಡಿ") || command.contains("ರದ್ದು") || command.contains("ನಾನು ಆರಾಮಾಗಿದ್ದೇನೆ") || command.contains("ನಾನು ಚೆನ್ನಾಗಿದ್ದೇನೆ") || command.contains("raddu madi") || command.contains("chennagiddene")) {
            return VoiceIntent.CancelEmergency
        }
        if (command.contains("emergency") || command.contains("sos") || command.contains("danger help") || command == "help me" ||
            command.contains("मदद करो") || command.contains("मेरी मदद करो") || command.contains("बचाओ") || command.contains("आपातकाल") || command.contains("आपतकालीन") || command.contains("आपातकालीन") || command.contains("खतरा") || command.contains("madad karo") || command.contains("bachao") || command.contains("khatra") ||
            command.contains("ಸಹಾಯ ಮಾಡಿ") || command.contains("ನನಗೆ ಸಹಾಯ ಮಾಡಿ") || command.contains("ಕಾಪಾಡಿ") || command.contains("ತುರ್ತು") || command.contains("ತುರ್ತು ಪರಿಸ್ಥಿತಿ") || command.contains("ಅಪಾಯ") || command.contains("sahaya madi") || command.contains("kapadi") || command.contains("thurtu")) {
            return VoiceIntent.EmergencySos
        }
        if (command.contains("call guardian") || command.contains("call helper") || command.contains("call contact") || command.contains("phone guardian") || command == "call" ||
            command.contains("अभिभावक") || command.contains("गार्जियन") || command.contains("गार्डियन") || command.contains("कॉल करें") || command.contains("कॉल करो") || command.contains("फोन करो") || command.contains("guardian ko call") ||
            command.contains("ಪೋಷಕರಿಗೆ") || command.contains("ರಕ್ಷಕರಿಗೆ") || command.contains("ಕರೆ ಮಾಡಿ") || command.contains("ಫೋನ್ ಮಾಡಿ") || command.contains("guardian ge call")) {
            return VoiceIntent.CallGuardian
        }
        if (command.contains("send location") || command.contains("send my location") || command.contains("text location") || command.contains("share location") || command.contains("distress location") ||
            command.contains("स्थान भेजें") || command.contains("मेरा स्थान भेजो") || command.contains("लोकेशन भेजो") || command.contains("लोकेशन शेयर") ||
            command.contains("ಸ್ಥಳ ಕಳುಹಿಸಿ") || command.contains("ನನ್ನ ಸ್ಥಳವನ್ನು ಕಳುಹಿಸಿ") || command.contains("ಲೊಕೇಶನ್ ಕಳುಹಿಸಿ")) {
            return VoiceIntent.SendLocationAlert
        }

        // 4. Navigation Lifecycle Commands
        if (command.contains("pause navigation") || command.contains("pause route") || command.contains("hold navigation") ||
            command.contains("नेविगेशन रोकें") || command.contains("रूट रोकें") || command.contains("विराम दें") ||
            command.contains("ನ್ಯಾವಿಗೇಷನ್ ವಿರಾಮಗೊಳಿಸಿ") || command.contains("ಸ್ವಲ್ಪ ನಿಲ್ಲಿಸಿ")) {
            return VoiceIntent.PauseNavigation
        }
        if (command.contains("resume navigation") || command.contains("resume route") || command.contains("continue navigation") || command.contains("continue walking") ||
            command.contains("नेविगेशन फिर से शुरू") || command.contains("आगे बढ़ो") || command.contains("जारी रखें") || command.contains("फिर से चलो") ||
            command.contains("ನ್ಯಾವಿಗೇಷನ್ ಪುನರಾರಂಭಿಸಿ") || command.contains("ಮುಂದುವರಿಸಿ") || command.contains("ನಡಿಗೆ ಮುಂದುವರಿಸಿ")) {
            return VoiceIntent.ResumeNavigation
        }
        if (command == "start" || command == "start navigation" || command == "start walking" || command == "begin" || command == "go" || command == "let's go" || command == "lets go" || command == "yes" || command == "yes start" || command == "confirm" || command == "start route" ||
            command == "शुरू करें" || command == "नेविगेशन शुरू" || command == "शुरू करो" || command == "प्रारंभ करें" || command == "चलें" || command == "हाँ" || command == "हाँ शुरू करो" ||
            command == "ಪ್ರಾರಂಭಿಸಿ" || command == "ನ್ಯಾವಿಗೇಷನ್ ಪ್ರಾರಂಭಿಸಿ" || command == "ಶುರು ಮಾಡಿ" || command == "ಹೋಗೋಣ" || command == "ಹೌದು") {
            return VoiceIntent.StartNavigation
        }
        if (command.contains("go home") || command.contains("take me home") || command.contains("navigate home") || command.contains("i want to go home") ||
            command.contains("घर चलो") || command.contains("मुझे घर ले चलो") || command.contains("घर जाना है") || command.contains("घर का रास्ता") ||
            command.contains("ಮನೆಗೆ ಹೋಗಿ") || command.contains("ನನ್ನನ್ನು ಮನೆಗೆ ಕರೆದೊಯ್ಯಿರಿ") || command.contains("ಮನೆಗೆ ಹೋಗಬೇಕು") || command.contains("ಮನೆಗೆ ದಾರಿ")) {
            return VoiceIntent.GoHome
        }

        // 5. Navigation Instruction & Inquiries
        if (command.contains("repeat instruction") || command.contains("repeat direction") || command.contains("repeat that") || command.contains("repeat") || command.contains("what was that") || command.contains("say again") || command.contains("again") || command.contains("what did you say") ||
            command.contains("दोहराएं") || command.contains("दोहराओ") || command.contains("फिर से बोलो") || command.contains("क्या कहा") || command.contains("फिर से कहो") || command.contains("phir se bolo") ||
            command.contains("ಮತ್ತೆ ಹೇಳಿ") || command.contains("ಮತ್ತೊಮ್ಮೆ ಹೇಳಿ") || command.contains("ಪುನರಾವರ್ತಿಸಿ") || command.contains("ಏನು ಹೇಳಿದ್ದೀರಿ") || command.contains("ಇನ್ನೊಮ್ಮೆ ಹೇಳಿ") || command.contains("innomme heli")) {
            return VoiceIntent.RepeatLast
        }
        if (command.contains("next instruction") || command.contains("next turn") || command.contains("where do i turn") || command.contains("next step") || command == "direction" || command == "directions" ||
            command.contains("अगला निर्देश") || command.contains("अगला मोड़") || command.contains("कहाँ मुड़ना है") || command.contains("दिशाएं") ||
            command.contains("ಮುಂದಿನ ನಿರ್ದೇಶನ") || command.contains("ಮುಂದಿನ ತಿರುವು") || command.contains("ಎಲ್ಲಿ ತಿರುಗಬೇಕು")) {
            return VoiceIntent.NextInstruction
        }
        if (command.contains("where am i") || command.contains("current location") || command.contains("tell me my address") || command.contains("my address") || command.contains("what is my location") || command.contains("where i am") || command == "location" ||
            command.contains("मैं कहाँ हूँ") || command.contains("मेरा स्थान") || command.contains("मेरा पता") || command.contains("वर्तमान स्थान") || command.contains("लोकेशन क्या है") || command.contains("लोकेशन बताओ") || command.contains("मेरी जगह") || command.contains("kahan hun") || command.contains("location batao") ||
            command.contains("ನಾನು ಎಲ್ಲಿದ್ದೇನೆ") || command.contains("ನನ್ನ ಸ್ಥಳ") || command.contains("ನನ್ನ ವಿಳಾಸ ಹೇಳಿ") || command.contains("ಪ್ರಸ್ತುತ ಸ್ಥಳ") || command.contains("ಸ್ಥಳ ಎಲ್ಲಿದೆ") || command.contains("yellidene")) {
            return VoiceIntent.LocationInquiry
        }
        if (command.contains("how far") || command.contains("distance left") || command.contains("remaining distance") || command.contains("how much distance") || command.contains("how long") || command.contains("eta") || command.contains("when will i arrive") ||
            command.contains("कितनी दूर") || command.contains("कितनी दूरी बची है") || command.contains("दूरी कितनी है") || command.contains("कब पहुँचेंगे") ||
            command.contains("ಎಷ್ಟು ದೂರ") || command.contains("ಇನ್ನೂ ಎಷ್ಟು ದೂರವಿದೆ") || command.contains("ದೂರ ಎಷ್ಟು") || command.contains("ಯಾವಾಗ ತಲುಪುತ್ತೇವೆ")) {
            return VoiceIntent.RemainingDistance
        }
        if (command.contains("is path clear") || command.contains("check path") || command.contains("what's ahead") || command.contains("what is ahead") || command.contains("obstacles ahead") || command.contains("path check") || command.contains("is it safe") ||
            command.contains("क्या रास्ता साफ है") || command.contains("आगे क्या है") || command.contains("सामने क्या है") || command.contains("रुकावट क्या है") || command.contains("रुकावट बताओ") || command.contains("बाधाएं बताएं") || command.contains("रास्ता चेक करो") || command.contains("सुरक्षित है क्या") || command.contains("kya hai samne") || command.contains("samne kya hai") ||
            command.contains("ದಾರಿ ಸ್ಪಷ್ಟವಾಗಿದೆಯೇ") || command.contains("ಮುಂದೆ ಏನಿದೆ") || command.contains("ಅಡಚಣೆ ಏನಿದೆ") || command.contains("ಅಡಚಣೆಗಳು ಇವೆಯೇ") || command.contains("ಸುರಕ್ಷಿತವಾಗಿದೆಯೇ") || command.contains("yenu ide munde") || command.contains("munde yenu ide")) {
            return VoiceIntent.ObstacleInquiry
        }

        // 6. Offline Areas Management
        if (command.contains("how much offline storage is available") || command.contains("offline storage") || command.contains("storage available") || command.contains("how much storage")) {
            return VoiceIntent.StorageInquiry
        }
        if (command == "download this area for offline navigation" || command == "download this area" || command == "save this area" || command == "download current area" || command == "save current area") {
            return VoiceIntent.DownloadCurrentArea
        }
        if (command.contains("offline maps") || command.contains("show offline maps") || command.contains("show downloaded areas") || command.contains("offline areas") || command.contains("downloaded areas")) {
            return VoiceIntent.ShowOfflineMaps
        }

        val deleteMatcher = DELETE_AREA_PATTERN.matcher(command)
        if (deleteMatcher.find()) {
            val target = deleteMatcher.group(1)?.trim() ?: ""
            if (target.isNotBlank()) {
                return VoiceIntent.DeleteOfflineArea(target)
            }
        }

        val downloadMatcher = DOWNLOAD_AREA_PATTERN.matcher(command)
        if (downloadMatcher.find()) {
            val target = downloadMatcher.group(1)?.trim() ?: ""
            if (target.isNotBlank() && target != "this area" && target != "current area") {
                return VoiceIntent.DownloadArea(target)
            } else if (target == "this area" || target == "current area") {
                return VoiceIntent.DownloadCurrentArea
            }
        }

        // 7. Settings Commands (Voice, Volume, Vibration, Speed)
        if (command.contains("turn voice guidance on") || command.contains("voice guidance on") || command.contains("voice on") || command.contains("unmute voice") || command.contains("enable voice") ||
            command.contains("आवाज चालू") || command.contains("ध्वनि चालू") || command.contains("वॉयस चालू") ||
            command.contains("ಧ್ವನಿ ಆನ್") || command.contains("ಧ್ವನಿ ಪ್ರಾರಂಭಿಸಿ")) {
            return VoiceIntent.ToggleVoiceGuidance(enable = true)
        }
        if (command.contains("turn voice guidance off") || command.contains("voice guidance off") || command.contains("voice off") || command.contains("mute voice") || command.contains("disable voice") ||
            command.contains("आवाज बंद") || command.contains("वॉयस म्यूट") || command.contains("ध्वनि बंद") ||
            command.contains("ಧ್ವನಿ ಆಫ್") || command.contains("ಧ್ವನಿ ಮ್ಯೂಟ್")) {
            return VoiceIntent.ToggleVoiceGuidance(enable = false)
        }
        if (command.contains("increase volume") || command.contains("volume up") || command.contains("louder") || command.contains("speak louder") || command.contains("turn up volume") ||
            command.contains("आवाज बढ़ाओ") || command.contains("तेज बोलो") || command.contains("वॉल्यूम बढ़ाओ") ||
            command.contains("ಧ್ವನಿ ಹೆಚ್ಚಿಸಿ") || command.contains("ಜೋರಾಗಿ ಮಾತನಾಡಿ") || command.contains("ವಾಲ್ಯೂಮ್ ಹೆಚ್ಚು ಮಾಡಿ")) {
            return VoiceIntent.AdjustVolume(increase = true)
        }
        if (command.contains("decrease volume") || command.contains("volume down") || command.contains("softer") || command.contains("speak softer") || command.contains("turn down volume") || command.contains("lower volume") ||
            command.contains("आवाज कम करो") || command.contains("धीमे बोलो") || command.contains("वॉल्यूम कम करो") ||
            command.contains("ಧ್ವನಿ ಕಡಿಮೆ ಮಾಡಿ") || command.contains("ಮೆಲ್ಲನೆ ಮಾತನಾಡಿ") || command.contains("ವಾಲ್ಯೂಮ್ ಕಡಿಮೆ ಮಾಡಿ")) {
            return VoiceIntent.AdjustVolume(increase = false)
        }
        if (command.contains("enable vibration") || command.contains("turn on vibration") || command.contains("vibration on") || command.contains("haptics on") ||
            command.contains("कंपन चालू") || command.contains("वाइब्रेशन ऑन") ||
            command.contains("ಕಂಪನ ಆನ್") || command.contains("ವೈಬ್ರೇಶನ್ ಆನ್")) {
            return VoiceIntent.ToggleVibration(enable = true)
        }
        if (command.contains("disable vibration") || command.contains("turn off vibration") || command.contains("vibration off") || command.contains("haptics off") ||
            command.contains("कंपन बंद") || command.contains("वाइब्रेशन ऑफ") ||
            command.contains("ಕಂಪನ ಆಫ್") || command.contains("ವೈಬ್ರೇಶನ್ ಆಫ್")) {
            return VoiceIntent.ToggleVibration(enable = false)
        }
        if (command.contains("faster") || command.contains("speed up") || command.contains("talk fast") || command.contains("speak faster") ||
            command.contains("तेज बोलो") || command.contains("जल्दी बोलो") ||
            command.contains("ವೇಗವಾಗಿ ಮಾತನಾಡಿ") || command.contains("ಬೇಗ ಹೇಳಿ")) {
            return VoiceIntent.AdjustSpeed(faster = true)
        }
        if (command.contains("slower") || command.contains("slow down") || command.contains("talk slow") || command.contains("speak slower") ||
            command.contains("धीरे बोलो") || command.contains("धीमे बोलो") ||
            command.contains("ನಿಧಾನವಾಗಿ ಮಾತನಾಡಿ")) {
            return VoiceIntent.AdjustSpeed(faster = false)
        }

        // 8. Hardware, Diagnostics & Vision Controls
        if (command.contains("demo mode") || command.contains("toggle demo") || command.contains("diagnostic mode") || command.contains("developer mode")) {
            return VoiceIntent.ToggleDemoMode
        }
        if (command.contains("light on") || command.contains("torch on") || command.contains("flash on") || command.contains("turn on light") ||
            command.contains("टॉर्च चालू") || command.contains("लाइट चालू") ||
            command.contains("ಟಾರ್ಚ್ ಆನ್") || command.contains("ಬೆಳಕು ಆನ್")) {
            return VoiceIntent.ToggleTorch(enable = true)
        }
        if (command.contains("light off") || command.contains("torch off") || command.contains("flash off") || command.contains("turn off light") ||
            command.contains("टॉर्च बंद") || command.contains("लाइट बंद") ||
            command.contains("ಟಾರ್ಚ್ ಆಫ್") || command.contains("ಬೆಳಕು ಆಫ್")) {
            return VoiceIntent.ToggleTorch(enable = false)
        }
        if (command.contains("pause detection") || command.contains("stop scan") || command.contains("stop detection") || command.contains("freeze camera") ||
            command.contains("स्कैन रोको") || command.contains("कैमरा रोको") ||
            command.contains("ಡಿಟೆಕ್ಷನ್ ನಿಲ್ಲಿಸಿ") || command.contains("ಕ್ಯಾಮರಾ ನಿಲ್ಲಿಸಿ")) {
            return VoiceIntent.ToggleDetection(pause = true)
        }
        if (command.contains("resume detection") || command.contains("resume scanning") || command.contains("resume scan") || command.contains("start scan") || command.contains("start detection") || command.contains("resume camera") ||
            command.contains("स्कैन शुरू") || command.contains("कैमरा चालू") ||
            command.contains("ಡಿಟೆಕ್ಷನ್ ಪುನರಾರಂಭಿಸಿ") || command.contains("ಕ್ಯಾಮರಾ ಆನ್")) {
            return VoiceIntent.ToggleDetection(pause = false)
        }
        if (command.contains("show map") || command.contains("hide map") || command.contains("toggle map") || command.contains("toggle view") || command.contains("split screen") || command.contains("switch view") || command.contains("change view") || command.contains("view mode") || command.contains("full camera") || command.contains("full map")) {
            return VoiceIntent.ToggleMap
        }
        if (command.contains("open maps") || command.contains("google maps") || command.contains("external map")) {
            return VoiceIntent.OpenMaps
        }

        // 9. Natural Language Destination Navigation Matching (English, Hindi, Kannada)
        val navMatcher = NAVIGATE_PATTERN.matcher(command)
        if (navMatcher.find()) {
            val dest = navMatcher.group(1)?.trim() ?: ""
            if (dest.isNotBlank() && dest != "google") {
                return VoiceIntent.NavigateTo(dest)
            }
        }
        val hiPrefixMatcher = HINDI_NAV_PREFIX.matcher(command)
        if (hiPrefixMatcher.find()) {
            val dest = hiPrefixMatcher.group(1)?.trim() ?: ""
            if (dest.isNotBlank()) return VoiceIntent.NavigateTo(dest)
        }
        val hiSuffixMatcher = HINDI_NAV_SUFFIX.matcher(command)
        if (hiSuffixMatcher.find()) {
            val dest = hiSuffixMatcher.group(1)?.trim() ?: ""
            if (dest.isNotBlank()) return VoiceIntent.NavigateTo(dest)
        }
        val knPrefixMatcher = KANNADA_NAV_PREFIX.matcher(command)
        if (knPrefixMatcher.find()) {
            val dest = knPrefixMatcher.group(1)?.trim() ?: ""
            if (dest.isNotBlank()) return VoiceIntent.NavigateTo(dest)
        }
        val knSuffixMatcher = KANNADA_NAV_SUFFIX.matcher(command)
        if (knSuffixMatcher.find()) {
            val dest = knSuffixMatcher.group(1)?.trim() ?: ""
            if (dest.isNotBlank()) return VoiceIntent.NavigateTo(dest)
        }

        // 10. Status & Help
        if (command.contains("history") || command.contains("past logs") || command.contains("recent detections") || command.contains("recent obstacles")) {
            return VoiceIntent.HistoryInquiry
        }
        if (command.contains("status") || command.contains("system health") || command.contains("how are you") ||
            command.contains("स्थिति") || command.contains("सब ठीक है") || command.contains("ಸ್ಥಿತಿ")) {
            return VoiceIntent.StatusInquiry
        }
        if (command.contains("help") || command.contains("commands") || command.contains("what can you do") || command.contains("options") ||
            command.contains("मदद") || command.contains("सहायता") || command.contains("ಆಜ್ಞೆಗಳು") || command.contains("ಸಹಾಯ") || command.contains("ಮಾರ್ಗದರ್ಶನ")) {
            return VoiceIntent.Help
        }

        return VoiceIntent.Unknown(rawText)
    }
}
