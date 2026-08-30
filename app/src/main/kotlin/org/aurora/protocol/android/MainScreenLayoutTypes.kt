package org.aurora.protocol.android

import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView

internal data class MainScreenViews(
    val importField: EditText,
    val importFieldError: TextView,
    val defaultImportFieldTextColor: Int,
    val importButton: Button,
    val removeProvisioningButton: Button,
    val connectButton: Button,
    val disconnectButton: Button,
    val progressIndicator: ProgressBar,
    val status: TextView,
    val statusLabelText: CharSequence,
)
