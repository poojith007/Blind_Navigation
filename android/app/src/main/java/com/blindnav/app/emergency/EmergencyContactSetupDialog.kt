package com.blindnav.app.emergency

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.blindnav.app.engine.IFeedbackEngine

class EmergencyContactSetupDialog(
    private val context: Context,
    private val repository: EmergencyContactRepository,
    private val feedbackEngine: IFeedbackEngine,
    private val onSaved: (EmergencyConfig) -> Unit = {},
    private val onDismiss: () -> Unit = {}
) {

    fun show() {
        val currentConfig = repository.getEmergencyConfig()

        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setOnDismissListener { onDismiss() }

        val scrollView = ScrollView(context).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Header Title
        val titleView = TextView(context).apply {
            text = "🛡️ Emergency Contact"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            contentDescription = "Emergency Contact Settings. Configure your primary Guardian and optional backup contact."
        }
        container.addView(titleView)

        val subtitleView = TextView(context).apply {
            text = "Configure your Guardian contact for emergency calls and location alerts. A valid phone number with 7 to 15 digits is required."
            textSize = 15f
            setTextColor(Color.parseColor("#CCCCCC"))
            setPadding(0, 16, 0, 32)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        container.addView(subtitleView)

        // Error message banner
        val errorBanner = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#FF5252"))
            setTypeface(typeface, Typeface.BOLD)
            visibility = View.GONE
            setPadding(20, 16, 20, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#33FF5252"))
                cornerRadius = 12f
                setStroke(2, Color.parseColor("#FF5252"))
            }
        }
        container.addView(errorBanner)

        // --- PRIMARY GUARDIAN ---
        val primaryHeader = TextView(context).apply {
            text = "PRIMARY GUARDIAN (Required)"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E676"))
            setPadding(0, 24, 0, 12)
        }
        container.addView(primaryHeader)

        val primaryNameLabel = createFieldLabel("Guardian Name:")
        container.addView(primaryNameLabel)

        val primaryNameInput = createInputField(
            hint = "e.g. John Doe or Mother",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
            initialValue = currentConfig.primaryGuardian?.name.orEmpty(),
            contentDesc = "Primary Guardian Name"
        )
        container.addView(primaryNameInput)

        val primaryPhoneLabel = createFieldLabel("Guardian Phone Number:")
        container.addView(primaryPhoneLabel)

        val primaryPhoneInput = createInputField(
            hint = "e.g. +91 9876543210 or 5550192834",
            inputType = InputType.TYPE_CLASS_PHONE,
            initialValue = currentConfig.primaryGuardian?.phoneNumber.orEmpty(),
            contentDesc = "Primary Guardian Phone Number"
        )
        container.addView(primaryPhoneInput)

        // --- BACKUP GUARDIAN (OPTIONAL) ---
        val backupHeader = TextView(context).apply {
            text = "BACKUP CONTACT (Optional)"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#40C4FF"))
            setPadding(0, 36, 0, 12)
        }
        container.addView(backupHeader)

        val backupNameLabel = createFieldLabel("Backup Contact Name:")
        container.addView(backupNameLabel)

        val backupNameInput = createInputField(
            hint = "e.g. Jane Doe (Optional)",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS,
            initialValue = currentConfig.backupGuardian?.name.orEmpty(),
            contentDesc = "Optional Backup Contact Name"
        )
        container.addView(backupNameInput)

        val backupPhoneLabel = createFieldLabel("Backup Contact Phone:")
        container.addView(backupPhoneLabel)

        val backupPhoneInput = createInputField(
            hint = "e.g. +91 9876543211 (Optional)",
            inputType = InputType.TYPE_CLASS_PHONE,
            initialValue = currentConfig.backupGuardian?.phoneNumber.orEmpty(),
            contentDesc = "Optional Backup Contact Phone"
        )
        container.addView(backupPhoneInput)

        // Action Buttons Row
        val buttonSpacing = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48)
        }
        container.addView(buttonSpacing)

        val saveButton = Button(context).apply {
            text = "💾 SAVE EMERGENCY CONTACTS"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00E676"))
                cornerRadius = 24f
            }
            contentDescription = "Save Emergency Contacts. Validates and saves your guardian settings."
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                160
            ).apply {
                setMargins(0, 0, 0, 24)
            }
            setOnClickListener {
                val primaryName = primaryNameInput.text.toString()
                val primaryPhone = primaryPhoneInput.text.toString()
                val backupName = backupNameInput.text.toString()
                val backupPhone = backupPhoneInput.text.toString()

                // Validate primary guardian
                val primaryValidation = GuardianContactValidator.validate(primaryName, primaryPhone)
                if (primaryValidation is ValidationResult.Error) {
                    errorBanner.text = primaryValidation.reason
                    errorBanner.visibility = View.VISIBLE
                    feedbackEngine.speakUrgent("Cannot save. ${primaryValidation.reason}")
                    return@setOnClickListener
                }

                // Validate optional backup guardian
                val backupValidation = GuardianContactValidator.validateOptionalBackup(backupName, backupPhone)
                if (backupValidation is ValidationResult.Error) {
                    errorBanner.text = "Backup contact: ${backupValidation.reason}"
                    errorBanner.visibility = View.VISIBLE
                    feedbackEngine.speakUrgent("Cannot save backup contact. ${backupValidation.reason}")
                    return@setOnClickListener
                }

                val validPrimary = (primaryValidation as ValidationResult.Success).contact
                val validBackup = (backupValidation as? ValidationResult.Success)?.contact

                repository.saveEmergencyConfig(validPrimary, validBackup)
                val newConfig = EmergencyConfig(validPrimary, validBackup)

                feedbackEngine.speakNormal("Emergency Guardian saved successfully: ${validPrimary.name}.")
                onSaved(newConfig)
                dialog.dismiss()
            }
        }
        container.addView(saveButton)

        val cancelButton = Button(context).apply {
            text = "CANCEL"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#666666"))
            }
            contentDescription = "Cancel and discard any changes."
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                140
            )
            setOnClickListener {
                feedbackEngine.speakNormal("Emergency contact setup cancelled.")
                dialog.dismiss()
            }
        }
        container.addView(cancelButton)

        scrollView.addView(container)
        dialog.setContentView(scrollView)

        feedbackEngine.speakNormal("Emergency contact settings. Configure your Guardian contact name and phone number.")
        dialog.show()
    }

    private fun createFieldLabel(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#B0B0B0"))
            setPadding(4, 8, 4, 6)
        }
    }

    private fun createInputField(
        hint: String,
        inputType: Int,
        initialValue: String,
        contentDesc: String
    ): EditText {
        return EditText(context).apply {
            this.hint = hint
            this.inputType = inputType
            setText(initialValue)
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#777777"))
            setPadding(32, 28, 32, 28)
            contentDescription = contentDesc
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#242424"))
                cornerRadius = 16f
                setStroke(2, Color.parseColor("#444444"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
        }
    }
}
