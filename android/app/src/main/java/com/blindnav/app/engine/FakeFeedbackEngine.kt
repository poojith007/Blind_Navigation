package com.blindnav.app.engine

/**
 * A pure-Kotlin stand-in for FeedbackEngine, used only in unit tests. Records every
 * speak/vibrate call instead of touching real Android TTS/Vibrator, so ThreatPrioritizer's
 * decision logic can be tested without an Android runtime or a connected device.
 */
class FakeFeedbackEngine : IFeedbackEngine {

    data class SpokenEvent(val text: String, val priority: SpeechPriority)

    val spoken = mutableListOf<SpokenEvent>()
    var dangerVibrations = 0
        private set
    var cautionVibrations = 0
        private set

    override var currentSpeechRate: Float = 1.0f
    override var lastSpokenMessage: String = ""
    override var currentPriority: SpeechPriority? = null
    override var isVoiceGuidanceMuted: Boolean = false
    override var isVibrationEnabled: Boolean = true
    override val isSpeaking: Boolean = false
    override var onSpeakingStateChanged: ((isSpeaking: Boolean) -> Unit)? = null

    override fun setSpeechRate(rate: Float) {
        currentSpeechRate = rate
    }

    override fun setVoiceGuidanceEnabled(enable: Boolean) {
        isVoiceGuidanceMuted = !enable
    }

    override fun adjustVolume(increase: Boolean) { /* no-op in tests */ }

    override fun speakWithPriority(text: String, priority: SpeechPriority) {
        if (isVoiceGuidanceMuted && priority != SpeechPriority.EMERGENCY) return
        spoken.add(SpokenEvent(text, priority))
        lastSpokenMessage = text
        currentPriority = priority
    }

    override fun speakUrgent(text: String) = speakWithPriority(text, SpeechPriority.EMERGENCY)
    override fun speakNormal(text: String) = speakWithPriority(text, SpeechPriority.SAFETY_WARNING)

    override fun repeatLastMessage() {
        if (lastSpokenMessage.isNotBlank()) speakWithPriority("Repeating: $lastSpokenMessage", SpeechPriority.SAFETY_WARNING)
    }

    override fun vibrateDanger() { dangerVibrations++ }
    override fun vibrateCaution() { cautionVibrations++ }
    override fun shutdown() { /* no-op in tests */ }
}