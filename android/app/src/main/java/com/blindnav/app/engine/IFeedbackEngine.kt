package com.blindnav.app.engine

interface IFeedbackEngine {
    val currentSpeechRate: Float
    val lastSpokenMessage: String
    fun setSpeechRate(rate: Float)
    fun speakUrgent(text: String)
    fun speakNormal(text: String)
    fun repeatLastMessage()
    fun vibrateDanger()
    fun vibrateCaution()
    fun shutdown()
}
