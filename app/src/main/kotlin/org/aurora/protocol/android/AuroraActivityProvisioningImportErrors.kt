package org.aurora.protocol.android

import android.view.View

internal fun AuroraActivity.showImportFailure(message: Int) {
    showLocalStatus(message)
    if (shouldShowImportFieldError(
            statusMessageId = message,
            invalidImportMessageId = R.string.status_import_invalid,
            saveFailedMessageId = R.string.status_import_save_failed,
            failedMessageId = R.string.status_import_failed,
        )
    ) {
        showImportFieldError(message)
    } else {
        clearImportFieldError()
    }
}

internal fun AuroraActivity.showImportFieldError(message: Int) {
    importFieldError.setText(message)
    importFieldError.visibility = View.VISIBLE
    importField.setTextColor(importFieldError.currentTextColor)
}

internal fun AuroraActivity.clearImportFieldError() {
    if (importFieldError.visibility != View.GONE) {
        importFieldError.visibility = View.GONE
        importFieldError.text = ""
        importField.setTextColor(defaultImportFieldTextColor)
    }
}
