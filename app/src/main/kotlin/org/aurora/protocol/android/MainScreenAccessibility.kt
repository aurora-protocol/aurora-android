package org.aurora.protocol.android

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

internal fun applyAccessibilityHint(view: View, hint: CharSequence) {
    view.accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.hintText = hint
        }
    }
}

internal fun statusContentDescription(
    statusLabel: CharSequence,
    statusText: CharSequence,
): String = "$statusLabel: $statusText"

internal fun applyStatusPresentation(
    statusView: TextView,
    statusLabel: CharSequence,
    statusText: CharSequence,
) {
    statusView.text = statusText
    statusView.contentDescription = statusContentDescription(statusLabel, statusText)
}
