package com.blindnav.app.offline

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
import com.blindnav.app.db.OfflineAreaEntity
import com.blindnav.app.engine.IFeedbackEngine

class OfflineAreasDialog(
    private val context: Context,
    private val offlineAreaManager: OfflineAreaManager,
    private val feedbackEngine: IFeedbackEngine,
    private val onDownloadCurrentArea: () -> Unit,
    private val onDownloadNewArea: () -> Unit,
    private val onDismiss: () -> Unit = {}
) {

    fun show() {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setOnDismissListener { onDismiss() }

        val scrollView = ScrollView(context).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Screen Title
        val titleView = TextView(context).apply {
            text = "🗺️ OFFLINE AREAS"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            contentDescription = "Offline Areas screen. Manage downloaded areas for offline walking navigation."
            setPadding(0, 0, 0, 8)
        }
        container.addView(titleView)

        val storageInfoView = TextView(context).apply {
            text = "Loading offline storage info..."
            textSize = 14f
            setTextColor(Color.parseColor("#80D8FF"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 24)
        }
        container.addView(storageInfoView)

        offlineAreaManager.getStorageSummary { used, free ->
            storageInfoView.text = "Offline Storage: $used used  •  $free free available"
            storageInfoView.contentDescription = "Offline storage: $used used. $free free available on device."
        }

        // 2. Download Current Area Button
        val downloadCurrentBtn = Button(context).apply {
            text = "⬇ DOWNLOAD CURRENT AREA"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00E676"))
                cornerRadius = 24f
            }
            contentDescription = "Download current area. Pre-caches local corridor and navigation points."
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                150
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            setOnClickListener {
                onDownloadCurrentArea()
                dialog.dismiss()
            }
        }
        container.addView(downloadCurrentBtn)

        // 3. Download Another Area Button
        val downloadAnotherBtn = Button(context).apply {
            text = "⬇ DOWNLOAD ANOTHER AREA"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1F1F1F"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#40C4FF"))
            }
            contentDescription = "Download another area by name. Tap to speak or type a region."
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                150
            ).apply {
                setMargins(0, 0, 0, 32)
            }
            setOnClickListener {
                onDownloadNewArea()
                dialog.dismiss()
            }
        }
        container.addView(downloadAnotherBtn)

        // 4. Downloaded Areas Section Header
        val sectionHeader = TextView(context).apply {
            text = "DOWNLOADED AREAS"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFD600"))
            setPadding(4, 0, 4, 12)
        }
        container.addView(sectionHeader)

        // List Container
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(listContainer)

        fun populateList(areas: List<OfflineAreaEntity>) {
            listContainer.removeAllViews()
            if (areas.isEmpty()) {
                val emptyView = TextView(context).apply {
                    text = "No offline areas downloaded yet."
                    textSize = 15f
                    setTextColor(Color.parseColor("#888888"))
                    setPadding(12, 16, 12, 16)
                }
                listContainer.addView(emptyView)
                return
            }

            areas.forEach { area ->
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 20, 24, 20)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1A1A1A"))
                        cornerRadius = 18f
                        setStroke(2, Color.parseColor("#333333"))
                    }
                    val p = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 16)
                    }
                    layoutParams = p
                    contentDescription = "Area ${area.areaName}. ${area.coverageDescription}. Size: ${area.formattedSize}. Available offline."
                }

                val topRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val nameText = TextView(context).apply {
                    text = area.areaName
                    textSize = 18f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                }
                topRow.addView(nameText)

                val statusBadge = TextView(context).apply {
                    text = "● AVAILABLE OFFLINE"
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#00E676"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1A00E676"))
                        cornerRadius = 10f
                    }
                    setPadding(14, 6, 14, 6)
                }
                topRow.addView(statusBadge)
                card.addView(topRow)

                val coverageText = TextView(context).apply {
                    text = area.coverageDescription
                    textSize = 14f
                    setTextColor(Color.parseColor("#CCCCCC"))
                    setPadding(0, 6, 0, 4)
                }
                card.addView(coverageText)

                val bottomRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 8, 0, 0)
                }

                val sizeText = TextView(context).apply {
                    text = "Storage: ${area.formattedSize}"
                    textSize = 13f
                    setTextColor(Color.parseColor("#90A4AE"))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                }
                bottomRow.addView(sizeText)

                val deleteBtn = Button(context).apply {
                    text = "🗑 DELETE"
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#FF5252"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#26FF5252"))
                        cornerRadius = 14f
                    }
                    contentDescription = "Delete downloaded area ${area.areaName} to free space."
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        90
                    )
                    setOnClickListener {
                        offlineAreaManager.deleteArea(area.id) {
                            feedbackEngine.speakNormal("Deleted offline area ${area.areaName}.")
                            offlineAreaManager.getAllDownloadedAreas { updated ->
                                populateList(updated)
                            }
                        }
                    }
                }
                bottomRow.addView(deleteBtn)
                card.addView(bottomRow)

                listContainer.addView(card)
            }
        }

        offlineAreaManager.getAllDownloadedAreas { areas ->
            populateList(areas)
        }

        // 5. Close Button
        val closeBtn = Button(context).apply {
            text = "✕ CLOSE"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#262626"))
                cornerRadius = 24f
            }
            contentDescription = "Close offline areas."
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                140
            ).apply {
                setMargins(0, 24, 0, 0)
            }
            setOnClickListener {
                dialog.dismiss()
            }
        }
        container.addView(closeBtn)

        scrollView.addView(container)
        dialog.setContentView(scrollView)

        feedbackEngine.speakNormal("Offline areas. You can download areas to navigate without internet.")
        dialog.show()
    }
}
