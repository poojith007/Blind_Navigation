package com.blindnav.app.voice

sealed class VoiceIntent {
    data object LocationInquiry : VoiceIntent()
    data object EmergencySos : VoiceIntent()
    data object CancelEmergency : VoiceIntent()
    data object RepeatLast : VoiceIntent()
    data object HistoryInquiry : VoiceIntent()
    data class ToggleDetection(val pause: Boolean) : VoiceIntent()
    data class AdjustSpeed(val faster: Boolean) : VoiceIntent()
    data class SetEmergencyContact(val phoneNumber: String) : VoiceIntent()
    data class ToggleTorch(val enable: Boolean) : VoiceIntent()
    data object OpenMaps : VoiceIntent()
    data object ToggleMap : VoiceIntent()
    data object NextDirection : VoiceIntent()
    data object StopNavigation : VoiceIntent()
    data class NavigateTo(val destination: String) : VoiceIntent()
    data object StatusInquiry : VoiceIntent()
    data object Help : VoiceIntent()
    data class Unknown(val rawText: String) : VoiceIntent()
}

object VoiceCommandParser {

    fun parse(rawText: String): VoiceIntent {
        val command = rawText.lowercase().trim()

        return when {
            // Cancel commands
            command.contains("cancel") || command.contains("abort") || command.contains("i am ok") || command.contains("false alarm") -> {
                VoiceIntent.CancelEmergency
            }

            // Contact configuration (must be evaluated BEFORE generic emergency keyword)
            command.startsWith("set contact") || command.contains("emergency contact") || command.contains("set phone") || command.contains("update contact") -> {
                val digits = command.filter { it.isDigit() }
                if (digits.length >= 3) {
                    VoiceIntent.SetEmergencyContact(digits)
                } else {
                    VoiceIntent.Unknown(rawText)
                }
            }

            // Emergency SOS commands
            command.contains("emergency") || command.contains("sos") || command.contains("danger help") -> {
                VoiceIntent.EmergencySos
            }

            // Location inquiry
            command.contains("where am i") || command.contains("location") || command.contains("address") || command.contains("where i am") -> {
                VoiceIntent.LocationInquiry
            }

            // Repeat last announcement
            command.contains("repeat") || command.contains("what was that") || command.contains("say again") || command.contains("again") -> {
                VoiceIntent.RepeatLast
            }

            // History inquiry
            command.contains("history") || command.contains("recent") || command.contains("obstacles") || command.contains("past logs") -> {
                VoiceIntent.HistoryInquiry
            }

            // Torch / Flashlight control
            command.contains("light on") || command.contains("torch on") || command.contains("flash on") || command.contains("turn on light") -> {
                VoiceIntent.ToggleTorch(enable = true)
            }
            command.contains("light off") || command.contains("torch off") || command.contains("flash off") || command.contains("turn off light") -> {
                VoiceIntent.ToggleTorch(enable = false)
            }

            // Detection toggles
            command.contains("pause") || command.contains("stop scan") || command.contains("stop detection") || command.contains("freeze") -> {
                VoiceIntent.ToggleDetection(pause = true)
            }
            command.contains("resume") || command.contains("start scan") || command.contains("start detection") || command.contains("continue") -> {
                VoiceIntent.ToggleDetection(pause = false)
            }

            // In-app Map display toggle
            command.contains("show map") || command.contains("hide map") || command.contains("toggle map") || command.contains("split screen") || command.contains("switch view") -> {
                VoiceIntent.ToggleMap
            }

            // Destination navigation commands
            command.startsWith("navigate to") || command.startsWith("take me to") || command.startsWith("directions to") || (command.startsWith("go to") && !command.contains("google")) -> {
                val prefix = when {
                    command.startsWith("navigate to") -> "navigate to"
                    command.startsWith("take me to") -> "take me to"
                    command.startsWith("directions to") -> "directions to"
                    command.startsWith("go to") -> "go to"
                    else -> ""
                }
                val dest = command.removePrefix(prefix).trim()
                if (dest.isNotBlank()) {
                    VoiceIntent.NavigateTo(dest)
                } else {
                    VoiceIntent.Unknown(rawText)
                }
            }

            // Navigation directions & maneuvers
            command.contains("next turn") || command.contains("direction") || command.contains("where do i turn") || command.contains("next step") -> {
                VoiceIntent.NextDirection
            }

            // Stop walking navigation
            command.contains("stop navigation") || command.contains("cancel navigation") || command.contains("end route") -> {
                VoiceIntent.StopNavigation
            }

            // Open external Google Maps app
            command.contains("open maps") || command.contains("google maps") || command.contains("external map") -> {
                VoiceIntent.OpenMaps
            }

            // Speed adjustment
            command.contains("faster") || command.contains("speed up") || command.contains("talk fast") || command.contains("speak faster") -> {
                VoiceIntent.AdjustSpeed(faster = true)
            }
            command.contains("slower") || command.contains("slow down") || command.contains("talk slow") || command.contains("speak slower") -> {
                VoiceIntent.AdjustSpeed(faster = false)
            }

            // System status
            command.contains("status") || command.contains("system health") || command.contains("how are you") -> {
                VoiceIntent.StatusInquiry
            }

            // Help menu
            command.contains("help") || command.contains("commands") || command.contains("what can you do") || command.contains("options") -> {
                VoiceIntent.Help
            }

            else -> {
                VoiceIntent.Unknown(rawText)
            }
        }
    }
}
