package com.blindnav.app.engine

enum class SpeechPriority(val level: Int) {
    EMERGENCY(1),
    SAFETY_WARNING(2),
    NAVIGATION(3),
    INFORMATION(4)
}

interface IFeedbackEngine {
    val currentSpeechRate: Float
    val lastSpokenMessage: String
    val currentPriority: SpeechPriority?
    var isVoiceGuidanceMuted: Boolean
    var isVibrationEnabled: Boolean

    fun setSpeechRate(rate: Float)
    fun setVoiceGuidanceEnabled(enable: Boolean)
    fun adjustVolume(increase: Boolean)
    fun speakWithPriority(text: String, priority: SpeechPriority)
    fun speakUrgent(text: String)
    fun speakNormal(text: String)
    fun repeatLastMessage()
    fun vibrateDanger()
    fun vibrateCaution()
    fun shutdown()
}
