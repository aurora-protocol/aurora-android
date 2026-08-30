package org.aurora.protocol.android

import android.app.Activity
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout

internal fun Activity.mainScreenCommandButton(label: Int, hint: Int, action: () -> Unit): Button =
    Button(this).apply {
        setText(label)
        setSingleLine(false)
        minHeight = mainScreenDp(48)
        minimumHeight = mainScreenDp(48)
        applyAccessibilityHint(this, getString(hint))
        setOnClickListener { action() }
    }

internal fun Activity.mainScreenDp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun Activity.mainScreenThemeColor(attribute: Int): Int {
    val resolved = TypedValue()
    check(theme.resolveAttribute(attribute, resolved, true)) {
        "required main-screen theme color is unavailable"
    }
    return if (resolved.resourceId != 0) getColor(resolved.resourceId) else resolved.data
}

internal fun Activity.mainScreenMatchWidth(topMargin: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        this.topMargin = topMargin
    }

internal fun Activity.mainScreenWrapContent(topMargin: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        this.topMargin = topMargin
    }

@Suppress("DEPRECATION")
internal fun Activity.applyMainScreenSystemWindowInsets(view: View, contentPadding: Int) {
    view.setPadding(contentPadding, contentPadding, contentPadding, contentPadding)
    view.setOnApplyWindowInsetsListener { target, insets ->
        val left: Int
        val top: Int
        val right: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val safeDrawing = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            left = safeDrawing.left
            top = safeDrawing.top
            right = safeDrawing.right
            bottom = maxOf(safeDrawing.bottom, keyboard.bottom)
        } else {
            left = insets.systemWindowInsetLeft
            top = insets.systemWindowInsetTop
            right = insets.systemWindowInsetRight
            bottom = insets.systemWindowInsetBottom
        }
        target.setPadding(
            contentPadding + left,
            contentPadding + top,
            contentPadding + right,
            contentPadding + bottom,
        )
        insets
    }
    view.requestApplyInsets()
}
