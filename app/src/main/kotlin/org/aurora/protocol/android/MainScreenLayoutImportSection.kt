package org.aurora.protocol.android

import android.app.Activity
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import org.aurora.protocol.android.core.ProvisioningImport

internal data class MainScreenImportSection(
    val importField: EditText,
    val importFieldError: TextView,
    val defaultImportFieldTextColor: Int,
)

internal fun Activity.buildMainScreenImportSection(onImportTextChanged: () -> Unit): MainScreenImportSection {
    val importField = EditText(this).apply {
        id = View.generateViewId()
        isSaveEnabled = false
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
        filters = arrayOf(InputFilter.LengthFilter(ProvisioningImport.maximumEncodedCharacters))
        setHint(R.string.provisioning_import_hint)
        applyAccessibilityHint(this, getString(R.string.provisioning_import_field_hint))
        setSingleLine(false)
        minLines = 4
    }
    val defaultImportFieldTextColor = importField.currentTextColor
    val importFieldError = TextView(this).apply {
        visibility = View.GONE
        setTextColor(mainScreenThemeColor(android.R.attr.colorError))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    importField.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(text: Editable?) {
            onImportTextChanged()
        }
    })
    return MainScreenImportSection(
        importField = importField,
        importFieldError = importFieldError,
        defaultImportFieldTextColor = defaultImportFieldTextColor,
    )
}

internal fun Activity.buildMainScreenImportLabel(importFieldId: Int): TextView =
    TextView(this).apply {
        setText(R.string.provisioning_import_label)
        labelFor = importFieldId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            isAccessibilityHeading = true
        }
    }
