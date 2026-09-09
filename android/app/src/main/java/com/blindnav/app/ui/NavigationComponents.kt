package com.blindnav.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.blindnav.app.location.GpsConfidence
import com.blindnav.app.network.ConnectionStatus

/**
 * Reusable, Material 3-aligned, high-contrast accessible UI components
 * specifically designed for visually impaired and low-vision pedestrians.
 */
object NavigationComponents {

    /**
     * Top status pill providing dual encoding (symbols + text) for immediate situational awareness.
     */
    fun createStatusPill(context: Context): TextView {
        return TextView(context).apply {
            text = "● PATH CLEAR  |  CONTINUE STRAIGHT"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E676"))
            setPadding(36, 18, 36, 18)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60D1117"))
                cornerRadius = 32f
                setStroke(2, Color.parseColor("#3300E676"))
            }
            elevation = 16f
            contentDescription = "System status: Path clear. Continue straight."
        }
    }

    /**
     * Dual mini indicator row for Network connection & GPS confidence.
     */
    fun createConnectionGpsRow(context: Context): ConnectionGpsViewHolder {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        val netBadge = TextView(context).apply {
            text = "🟢 ONLINE"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E676"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC121212"))
                cornerRadius = 14f
                setStroke(1, Color.parseColor("#00E676"))
            }
            setPadding(18, 6, 18, 6)
            contentDescription = "Network status: Online."
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 8, 0)
            }
        }
        container.addView(netBadge)

        val gpsBadge = TextView(context).apply {
            text = "🟢 GPS GOOD"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E676"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC121212"))
                cornerRadius = 14f
                setStroke(1, Color.parseColor("#00E676"))
            }
            setPadding(18, 6, 18, 6)
            contentDescription = "GPS accuracy: Good."
        }
        container.addView(gpsBadge)

        return ConnectionGpsViewHolder(container, netBadge, gpsBadge)
    }

    /**
     * Contextual banner for hardware/system state (GPS weak, Offline, Camera unavailable).
     */
    fun createSystemBanner(context: Context): TextView {
        return TextView(context).apply {
            visibility = View.GONE
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(24, 16, 24, 16)
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            elevation = 14f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFD600"))
                cornerRadius = 16f
            }
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 10)
            }
            layoutParams = p
        }
    }

    /**
     * Large, high-contrast accessible Voice Control Button.
     * Primary interaction mechanism for visually impaired users.
     */
    fun createVoiceButton(context: Context, onClick: () -> Unit): Button {
        return Button(context).apply {
            text = "🎤 VOICE COMMAND"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1B2228"))
                cornerRadius = 28f
                setStroke(3, Color.parseColor("#00E5FF"))
            }
            elevation = 14f
            contentDescription = "Voice command. Double tap to start listening."
            layoutParams = LinearLayout.LayoutParams(
                0,
                150,
                1.0f
            ).apply {
                setMargins(0, 0, 8, 0)
            }
            setOnClickListener { onClick() }
        }
    }

    /**
     * High-contrast Emergency SOS anchor button.
     */
    fun createEmergencyButton(context: Context, onClick: () -> Unit): Button {
        return Button(context).apply {
            text = "🚨 EMERGENCY"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D50000"))
                cornerRadius = 28f
                setStroke(3, Color.parseColor("#FF8A80"))
            }
            elevation = 16f
            contentDescription = "Emergency SOS button. Double tap to trigger emergency assistance."
            layoutParams = LinearLayout.LayoutParams(
                0,
                150,
                1.0f
            ).apply {
                setMargins(8, 0, 0, 0)
            }
            setOnClickListener { onClick() }
        }
    }

    data class ConnectionGpsViewHolder(
        val container: LinearLayout,
        val netBadge: TextView,
        val gpsBadge: TextView
    ) {
        fun updateNetwork(status: ConnectionStatus) {
            netBadge.text = "${status.displayIcon} ${status.label}"
            val color = when (status) {
                ConnectionStatus.ONLINE -> Color.parseColor("#00E676")
                ConnectionStatus.OFFLINE -> Color.parseColor("#FF5252")
                ConnectionStatus.SYNCING, ConnectionStatus.DOWNLOADING -> Color.parseColor("#00E5FF")
            }
            netBadge.setTextColor(color)
            (netBadge.background as? GradientDrawable)?.setStroke(1, color)
            netBadge.contentDescription = "Network status: ${status.label}."
        }

        fun updateGps(confidence: GpsConfidence) {
            gpsBadge.text = "${confidence.displayIcon} ${confidence.label}"
            val color = when (confidence) {
                GpsConfidence.GOOD -> Color.parseColor("#00E676")
                GpsConfidence.WEAK -> Color.parseColor("#FFD600")
                GpsConfidence.UNAVAILABLE -> Color.parseColor("#FF5252")
            }
            gpsBadge.setTextColor(color)
            (gpsBadge.background as? GradientDrawable)?.setStroke(1, color)
            gpsBadge.contentDescription = "GPS status: ${confidence.label}."
        }
    }
}
