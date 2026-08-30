package org.aurora.protocol.android

import org.aurora.protocol.android.core.AuroraLog
import java.util.concurrent.RejectedExecutionException

internal fun AuroraActivity.removeProvisioning() {
    val controls = currentControls()
    if (!controls.removeProvisioningEnabled) {
        showLocalStatus(R.string.status_remove_provisioning_failed)
        refreshControls()
        return
    }
    (application as AuroraApplication).provisioningAvailability.invalidate()
    val lease = provisioningStorageOperations.begin(ProvisioningStorageOperation.REMOVING) ?: run {
        showLocalStatus(R.string.status_remove_provisioning_failed)
        refreshControls()
        return
    }
    var message = R.string.status_remove_provisioning_failed
    val storageCommand = ProvisioningStorageCommand(
        operations = provisioningStorageOperations,
        lease = lease,
        work = {
            message = try {
                (application as AuroraApplication).reservations.removeProvisioning()
                R.string.status_provisioning_removed
            } catch (error: Exception) {
                AuroraLog.debug("provisioning removal", error)
                R.string.status_remove_provisioning_failed
            }
        },
        afterCompletion = {
            if (message == R.string.status_provisioning_removed) {
                (application as AuroraApplication).provisioningAvailability.recordProvisioningRemoved()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                showLocalStatus(message)
                refreshControls()
            }
        },
    )
    showLocalStatus(R.string.status_removing_provisioning)
    refreshControls()
    try {
        worker.execute(storageCommand)
    } catch (error: RejectedExecutionException) {
        storageCommand.discardIfQueued()
        showLocalStatus(R.string.status_remove_provisioning_failed)
        refreshControls()
    }
}
