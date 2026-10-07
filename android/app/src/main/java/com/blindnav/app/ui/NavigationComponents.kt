package com.blindnav.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.blindnav.app.voice.VoiceState

/**
 * Reusable, high-contrast accessible UI components for the assistive navigation screen.
 *
 * Redesign notes (see conversation history for full rationale):
 * - One StatusBanner replaces four previously-separate always-on widgets
 *   (status pill, voice badge, system banner, obstacle alert card). Only one
 *   message is ever shown to the user at a time, chosen by priority in MainActivity.
 * - The standalone network/GPS badge row and the voice status badge have been
 *   removed entirely — that information now surfaces through the single banner
 *   (for GPS/network problems) or the voice button itself (for voice state),
 *   instead of three overlapping mechanisms.
 * - All sizes are dp via UiKit.dp()/dpf(), not raw pixels.
 */
object NavigationComponents {

    data class StatusBannerViews(
        val container: LinearLayout,
        val iconView: TextView,
        val messageView: TextView
    )

    /**
     * The single top-of-screen status banner. Every state (calm guidance, obstacle
     * warning, GPS/offline/camera problems, arrival) is expressed through this one
     * view via [updateStatusBanner], so only one message ever competes for attention.
     */
    fun createStatusBanner(context: Context): StatusBannerViews {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
            background = pill(context, Palette.cardBg, Palette.safe, strokeWidthDp = 1, radiusDp = 16)
            elevation = context.dpf(4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, context.dp(8)) }
        }

        val icon = TextView(context).apply {
            textSize = 18f
            setPadding(0, 0, context.dp(10), 0)
        }
        container.addView(icon)

        val message = TextView(context).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.safe)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        container.addView(message)

        return StatusBannerViews(container, icon, message)
    }

    fun updateStatusBanner(views: StatusBannerViews, icon: String, message: String, color: Int) {
        views.iconView.text = icon
        views.messageView.text = message
        views.messageView.setTextColor(color)
        (views.container.background as? GradientDrawable)?.setStroke(views.container.dp(1), color)
        views.container.contentDescription = message
    }

    /**
     * Voice control button. Its own text/color now carries all voice-state information
     * (listening / thinking / speaking / muted) — there is no separate voice badge.
     */
    fun createVoiceButton(
        context: Context,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null
    ): Button {
        return Button(context).apply {
            text = "🎙 Voice"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.white)
            background = pill(context, Color.parseColor("#151D28"), Palette.info, strokeWidthDp = 2, radiusDp = 24)
            elevation = context.dpf(6)
            contentDescription = "Voice Assistant button. Microphone active. Tap to speak command or long press to toggle mute."
            layoutParams = LinearLayout.LayoutParams(0, context.dp(52), 1.0f).apply {
                setMargins(0, 0, context.dp(8), 0)
            }
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                setOnLongClickListener {
                    onLongClick()
                    true
                }
            }
        }
    }

    fun updateVoiceButton(button: Button, state: VoiceState) {
        val (label, color, desc) = when (state) {
            VoiceState.MUTED -> Triple("🔇 Muted", Palette.danger, "Microphone muted. Tap to turn voice listening on.")
            VoiceState.PROCESSING -> Triple("⋯ Thinking", Palette.caution, "Voice Assistant processing your command.")
            VoiceState.SPEAKING -> Triple("🔊 Speaking", Palette.safe, "Voice Assistant speaking.")
            VoiceState.UNAVAILABLE -> Triple("⚠ Unavailable", Palette.muted, "Voice recognition unavailable on this device.")
            VoiceState.LISTENING -> Triple("🎙 Voice", Palette.info, "Voice Assistant active and listening. Speak a command.")
        }
        button.text = label
        button.setTextColor(if (state == VoiceState.MUTED) Palette.white else Palette.white)
        (button.background as? GradientDrawable)?.setStroke(button.dp(2), color)
        button.contentDescription = desc
    }

    /**
     * Emergency SOS / Guardian button. Tap = call guardian, long-press = distress SMS.
     */
    fun createEmergencyButton(
        context: Context,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null
    ): Button {
        return Button(context).apply {
            text = "🛡 SOS"
            textSize = 15f
            setTextColor(Palette.white)
            setTypeface(typeface, Typeface.BOLD)
            background = pill(context, Color.parseColor("#C62828"), Color.parseColor("#FF8A80"), strokeWidthDp = 2, radiusDp = 24)
            elevation = context.dpf(6)
            contentDescription = "Guardian SOS button. Tap to call guardian. Long press for instant emergency distress SMS."
            layoutParams = LinearLayout.LayoutParams(0, context.dp(52), 1.0f).apply {
                setMargins(context.dp(8), 0, 0, 0)
            }
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                setOnLongClickListener { onLongClick(); true }
            }
        }
    }
}