package org.aurora.protocol.android

import org.aurora.protocol.android.core.AuroraLog
import org.aurora.protocol.android.core.ProvisioningImport

internal class ProvisioningImportOutcome {
    var message: Int = R.string.status_import_failed
    var importedExpiryUnix: Long? = null
}

internal fun AuroraActivity.buildProvisioningImportCommand(
    lease: ProvisioningStorageOperationLease,
    encoded: CharArray,
): ProvisioningStorageCommand {
    val outcome = ProvisioningImportOutcome()
    return ProvisioningStorageCommand(
        operations = provisioningStorageOperations,
        lease = lease,
        work = {
            val ownsInput = synchronized(importInputLock) {
                if (pendingImport !== encoded) {
                    false
                } else {
                    pendingImport = null
                    true
                }
            }
            if (!ownsInput || Thread.currentThread().isInterrupted) {
                encoded.fill('\u0000')
            } else {
                val request = try {
                    ProvisioningImport.decode(encoded)
                } catch (error: IllegalArgumentException) {
                    AuroraLog.debug("provisioning import", error)
                    encoded.fill('\u0000')
                    outcome.message = R.string.status_import_invalid
                    null
                }
                if (request != null) {
                    try {
                        outcome.importedExpiryUnix = consumeProvisioningImportRequest(
                            request = request,
                            interrupted = { Thread.currentThread().isInterrupted },
                            reserveAndPersist = { ownedRequest ->
                                (application as AuroraApplication).reservations.reserveAndPersist(
                                    ownedRequest,
                                    System.currentTimeMillis() / 1_000,
                                )
                            },
                        )
                        if (outcome.importedExpiryUnix != null) {
                            outcome.message = R.string.status_import_succeeded
                        }
                    } catch (error: Exception) {
                        AuroraLog.debug("provisioning import", error)
                        outcome.message = R.string.status_import_save_failed
                    }
                }
            }
        },
        afterCompletion = {
            outcome.importedExpiryUnix?.let {
                (application as AuroraApplication).provisioningAvailability.recordImportedReservation(it)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                requestState.completeImport()
                showImportFailure(outcome.message)
                refreshControls()
            }
        },
    )
}

internal fun consumeProvisioningImportRequest(
    request: ByteArray,
    interrupted: () -> Boolean,
    reserveAndPersist: (ByteArray) -> Long,
): Long? = try {
    if (interrupted()) null else reserveAndPersist(request)
} finally {
    request.fill(0)
}
