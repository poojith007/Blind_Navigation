package com.blindnav.app.emergency

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.blindnav.app.engine.IFeedbackEngine

class EmergencySosDialog(
    private val context: Context,
    private val repository: EmergencyContactRepository,
    private val emergencySosHandler: EmergencySosHandler,
    private val feedbackEngine: IFeedbackEngine,
    private val onOpenSettings: () -> Unit
) {

    fun show() {
        val config = repository.getEmergencyConfig()
        val primaryGuardian = config.primaryGuardian

        if (primaryGuardian == null || !config.hasPrimaryGuardian) {
            feedbackEngine.speakUrgent("No emergency contact configured! Please configure your Guardian contact.")
            onOpenSettings()
            return
        }

        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val scrollView = ScrollView(context).apply {
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            isFillViewport = true
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. EMERGENCY Title
        val titleView = TextView(context).apply {
            text = "🚨 EMERGENCY"
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF1744"))
            gravity = Gravity.CENTER_HORIZONTAL
            contentDescription = "Emergency Screen. Primary guardian is ${primaryGuardian.name}."
            setPadding(0, 0, 0, 12)
        }
        container.addView(titleView)

        val guardianInfoView = TextView(context).apply {
            text = "Guardian: ${primaryGuardian.name} (${primaryGuardian.phoneNumber})"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFD600"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 48)
        }
        container.addView(guardianInfoView)

        // 2. Call Guardian Button (Primary Emergency Action)
        val callGuardianButton = Button(context).apply {
            text = "📞 CALL GUARDIAN"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D50000"))
                cornerRadius = 28f
                setStroke(3, Color.parseColor("#FF8A80"))
            }
            contentDescription = "Call Guardian ${primaryGuardian.name}. Tap to place emergency call immediately."
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                180
            ).apply {
                setMargins(0, 0, 0, 24)
            }
            setOnClickListener {
                emergencySosHandler.callGuardian(primaryGuardian)
                dialog.dismiss()
            }
        }
        container.addView(callGuardianButton)

        // 3. Send Location Button
        val sendLocationButton = Button(context).apply {
            text = "📍 SEND LOCATION"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00E676"))
                cornerRadius = 28f
                setStroke(3, Color.WHITE)
            }
            contentDescription = "Send Location. Tap to send emergency SMS distress message with your GPS coordinates to ${primaryGuardian.name}."
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                180
            ).apply {
                setMargins(0, 0, 0, 24)
            }
            setOnClickListener {
                emergencySosHandler.sendLocationAlert(
                    contact = primaryGuardian,
                    backupContact = config.backupGuardian
                )
                dialog.dismiss()
            }
        }
        container.addView(sendLocationButton)

        // Optional: Call Backup Guardian Button (if configured)
        val backupGuardian = config.backupGuardian
        if (backupGuardian != null && backupGuardian.phoneNumber.isNotBlank()) {
            val callBackupButton = Button(context).apply {
                text = "📞 CALL BACKUP (${backupGuardian.name})"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E65100"))
                    cornerRadius = 28f
                    setStroke(2, Color.parseColor("#FFB74D"))
                }
                contentDescription = "Call Backup Guardian ${backupGuardian.name} at ${backupGuardian.phoneNumber}."
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    160
                ).apply {
                    setMargins(0, 0, 0, 24)
                }
                setOnClickListener {
                    emergencySosHandler.callGuardian(backupGuardian)
                    dialog.dismiss()
                }
            }
            container.addView(callBackupButton)
        }

        // 4. Cancel Button
        val cancelButton = Button(context).apply {
            text = "✕ CANCEL"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#262626"))
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#757575"))
            }
            contentDescription = "Cancel emergency alert."
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                150
            )
            setOnClickListener {
                feedbackEngine.speakNormal("Emergency cancelled.")
                dialog.dismiss()
            }
        }
        container.addView(cancelButton)

        scrollView.addView(container)
        dialog.setContentView(scrollView)

        feedbackEngine.speakUrgent("Emergency menu. Tap Call Guardian to call ${primaryGuardian.name}, or tap Send Location to text your coordinates.")
        dialog.show()
    }
}
