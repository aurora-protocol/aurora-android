package org.aurora.protocol.android

import org.aurora.protocol.android.core.ProvisioningImport
import java.util.concurrent.RejectedExecutionException

internal fun AuroraActivity.importProvisioning() {
    if (!currentControls().importEnabled) {
        return
    }
    if (!requestState.beginImport()) {
        return
    }
    val lease = provisioningStorageOperations.begin(ProvisioningStorageOperation.IMPORTING) ?: run {
        requestState.completeImport()
        showImportFailure(R.string.status_import_save_failed)
        refreshControls()
        return
    }
    val editable = importField.text
    if (!ProvisioningImport.hasValidEncodedLength(editable.length)) {
        editable.clear()
        requestState.completeImport()
        provisioningStorageOperations.complete(lease)
        showImportFailure(R.string.status_import_invalid)
        refreshControls()
        return
    }
    val encoded = CharArray(editable.length) { editable[it] }
    editable.clear()
    synchronized(importInputLock) {
        pendingImport = encoded
    }
    (application as AuroraApplication).provisioningAvailability.invalidate()
    showLocalStatus(R.string.status_importing)
    refreshControls()
    val storageCommand = buildProvisioningImportCommand(lease, encoded)
    try {
        worker.execute(storageCommand)
    } catch (_: RejectedExecutionException) {
        synchronized(importInputLock) {
            if (pendingImport === encoded) {
                pendingImport = null
            }
            encoded.fill('\u0000')
        }
        storageCommand.discardIfQueued()
        requestState.completeImport()
        showImportFailure(R.string.status_import_save_failed)
        refreshControls()
    }
}
