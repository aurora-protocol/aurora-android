package org.aurora.protocol.android

import android.app.Activity
import android.os.Build
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView

internal data class MainScreenStatusSection(
    val progressIndicator: ProgressBar,
    val statusLabel: TextView,
    val status: TextView,
    val statusLabelText: CharSequence,
)

internal fun Activity.buildMainScreenStatusSection(initialStatusMessageId: Int): MainScreenStatusSection {
    val status = TextView(this).apply {
        id = View.generateViewId()
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val statusLabel = TextView(this).apply {
        setText(R.string.status_label)
        labelFor = status.id
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            isAccessibilityHeading = true
        }
    }
    val statusLabelText = statusLabel.text
    applyStatusPresentation(status, statusLabelText, getString(initialStatusMessageId))
    val progressIndicator = ProgressBar(this).apply {
        isIndeterminate = true
        contentDescription = getString(R.string.status_progress_content_description)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }
    return MainScreenStatusSection(
        progressIndicator = progressIndicator,
        statusLabel = statusLabel,
        status = status,
        statusLabelText = statusLabelText,
    )
}
