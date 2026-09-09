package com.blindnav.app.voice

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

        // 0. Contact Setup (check before general "emergency" keyword)
        if (command.startsWith("set contact") || command.contains("emergency contact") || command.contains("set phone") || command.contains("update contact")) {
            val digits = command.filter { it.isDigit() }
            return if (digits.length >= 3) {
                VoiceIntent.SetEmergencyContact(digits)
            } else {
                VoiceIntent.Unknown(rawText)
            }
        }

        // 1. Navigation Stop & Cancel Commands (check before general "cancel" or "stop")
        if (command.contains("stop navigation") || command.contains("cancel navigation") || command.contains("end route") || command.contains("stop route") || command.contains("stop walking") || command.contains("end navigation")) {
            return VoiceIntent.StopNavigation
        }

        // 2. Critical Emergency & Safety
        if (command == "stop" || command == "halt" || command == "freeze") {
            return VoiceIntent.StopSafety
        }
        if (command.contains("cancel") || command.contains("abort") || command.contains("i am ok") || command.contains("false alarm")) {
            return VoiceIntent.CancelEmergency
        }
        if (command.contains("emergency") || command.contains("sos") || command.contains("danger help") || command == "help me") {
            return VoiceIntent.EmergencySos
        }
        if (command.contains("call guardian") || command.contains("call helper") || command.contains("call contact") || command.contains("phone guardian") || command == "call") {
            return VoiceIntent.CallGuardian
        }
        if (command.contains("send location") || command.contains("send my location") || command.contains("text location") || command.contains("share location") || command.contains("distress location")) {
            return VoiceIntent.SendLocationAlert
        }

        // 3. Navigation Lifecycle Commands
        if (command.contains("pause navigation") || command.contains("pause route") || command.contains("hold navigation")) {
            return VoiceIntent.PauseNavigation
        }
        if (command.contains("resume navigation") || command.contains("resume route") || command.contains("continue navigation") || command.contains("continue walking")) {
            return VoiceIntent.ResumeNavigation
        }
        if (command == "start" || command == "start navigation" || command == "start walking" || command == "begin" || command == "go" || command == "let's go" || command == "lets go" || command == "yes" || command == "yes start" || command == "confirm" || command == "start route") {
            return VoiceIntent.StartNavigation
        }
        if (command.contains("go home") || command.contains("take me home") || command.contains("navigate home") || command.contains("i want to go home")) {
            return VoiceIntent.GoHome
        }

        // 3. Navigation Instruction & Inquiries
        if (command.contains("repeat instruction") || command.contains("repeat direction") || command.contains("repeat that") || command.contains("repeat") || command.contains("what was that") || command.contains("say again") || command.contains("again") || command.contains("what did you say")) {
            return VoiceIntent.RepeatLast
        }
        if (command.contains("next instruction") || command.contains("next turn") || command.contains("where do i turn") || command.contains("next step") || command == "direction" || command == "directions") {
            return VoiceIntent.NextInstruction
        }
        if (command.contains("where am i") || command.contains("current location") || command.contains("tell me my address") || command.contains("my address") || command.contains("what is my location") || command.contains("where i am") || command == "location") {
            return VoiceIntent.LocationInquiry
        }
        if (command.contains("how far") || command.contains("distance left") || command.contains("remaining distance") || command.contains("how much distance") || command.contains("how long") || command.contains("eta") || command.contains("when will i arrive")) {
            return VoiceIntent.RemainingDistance
        }
        if (command.contains("is path clear") || command.contains("check path") || command.contains("what's ahead") || command.contains("what is ahead") || command.contains("obstacles ahead") || command.contains("path check") || command.contains("is it safe")) {
            return VoiceIntent.ObstacleInquiry
        }

        // 4. Offline Areas Management
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

        // 5. Settings Commands (Voice, Volume, Vibration, Speed)
        if (command.contains("turn voice guidance on") || command.contains("voice guidance on") || command.contains("voice on") || command.contains("unmute voice") || command.contains("enable voice")) {
            return VoiceIntent.ToggleVoiceGuidance(enable = true)
        }
        if (command.contains("turn voice guidance off") || command.contains("voice guidance off") || command.contains("voice off") || command.contains("mute voice") || command.contains("disable voice")) {
            return VoiceIntent.ToggleVoiceGuidance(enable = false)
        }
        if (command.contains("increase volume") || command.contains("volume up") || command.contains("louder") || command.contains("speak louder") || command.contains("turn up volume")) {
            return VoiceIntent.AdjustVolume(increase = true)
        }
        if (command.contains("decrease volume") || command.contains("volume down") || command.contains("softer") || command.contains("speak softer") || command.contains("turn down volume") || command.contains("lower volume")) {
            return VoiceIntent.AdjustVolume(increase = false)
        }
        if (command.contains("enable vibration") || command.contains("turn on vibration") || command.contains("vibration on") || command.contains("haptics on")) {
            return VoiceIntent.ToggleVibration(enable = true)
        }
        if (command.contains("disable vibration") || command.contains("turn off vibration") || command.contains("vibration off") || command.contains("haptics off")) {
            return VoiceIntent.ToggleVibration(enable = false)
        }
        if (command.contains("faster") || command.contains("speed up") || command.contains("talk fast") || command.contains("speak faster")) {
            return VoiceIntent.AdjustSpeed(faster = true)
        }
        if (command.contains("slower") || command.contains("slow down") || command.contains("talk slow") || command.contains("speak slower")) {
            return VoiceIntent.AdjustSpeed(faster = false)
        }

        // 6. Contact Setup
        if (command.startsWith("set contact") || command.contains("emergency contact") || command.contains("set phone") || command.contains("update contact")) {
            val digits = command.filter { it.isDigit() }
            return if (digits.length >= 3) {
                VoiceIntent.SetEmergencyContact(digits)
            } else {
                VoiceIntent.Unknown(rawText)
            }
        }

        // 7. Hardware & Vision Controls
        if (command.contains("light on") || command.contains("torch on") || command.contains("flash on") || command.contains("turn on light")) {
            return VoiceIntent.ToggleTorch(enable = true)
        }
        if (command.contains("light off") || command.contains("torch off") || command.contains("flash off") || command.contains("turn off light")) {
            return VoiceIntent.ToggleTorch(enable = false)
        }
        if (command.contains("pause detection") || command.contains("stop scan") || command.contains("stop detection") || command.contains("freeze camera")) {
            return VoiceIntent.ToggleDetection(pause = true)
        }
        if (command.contains("resume detection") || command.contains("resume scanning") || command.contains("resume scan") || command.contains("start scan") || command.contains("start detection") || command.contains("resume camera")) {
            return VoiceIntent.ToggleDetection(pause = false)
        }
        if (command.contains("show map") || command.contains("hide map") || command.contains("toggle map") || command.contains("split screen") || command.contains("switch view")) {
            return VoiceIntent.ToggleMap
        }
        if (command.contains("open maps") || command.contains("google maps") || command.contains("external map")) {
            return VoiceIntent.OpenMaps
        }

        // 8. Natural Language Destination Navigation Matching
        val navMatcher = NAVIGATE_PATTERN.matcher(command)
        if (navMatcher.find()) {
            val dest = navMatcher.group(1)?.trim() ?: ""
            if (dest.isNotBlank() && dest != "google") {
                return VoiceIntent.NavigateTo(dest)
            }
        }

        // 9. Status & Help
        if (command.contains("history") || command.contains("past logs") || command.contains("recent detections") || command.contains("recent obstacles")) {
            return VoiceIntent.HistoryInquiry
        }
        if (command.contains("status") || command.contains("system health") || command.contains("how are you")) {
            return VoiceIntent.StatusInquiry
        }
        if (command.contains("help") || command.contains("commands") || command.contains("what can you do") || command.contains("options")) {
            return VoiceIntent.Help
        }

        return VoiceIntent.Unknown(rawText)
    }
}
