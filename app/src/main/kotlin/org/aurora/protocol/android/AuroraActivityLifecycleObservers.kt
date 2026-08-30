package org.aurora.protocol.android

internal fun AuroraActivity.resumeMainScreenObservers() {
    screenResumed = true
    val generation = ++statusObserverGeneration
    val observation = vpnTunnelStatus.observeCurrentPublication { update ->
        runOnUiThread {
            if (generation == statusObserverGeneration) {
                renderTunnelStatus(update)
            }
        }
    }
    renderTunnelStatus(observation.publication)
    statusObserver = observation.unsubscribe
    val storageObservation = provisioningStorageOperations.observeCurrent { update ->
        runOnUiThread {
            if (generation == statusObserverGeneration) {
                renderStorageOperation(update)
            }
        }
    }
    renderStorageOperation(storageObservation.publication)
    storageOperationObserver = storageObservation.unsubscribe
    val mayRefreshProvisioning = provisioningRefreshAllowed(
        importInProgress = requestState.importInProgress,
        storageOperationInProgress = storageOperationInProgress,
        connectRequested = requestState.connectRequested,
        pendingVpnServiceCommand = vpnServiceRequestTracker.pending?.command,
    )
    (application as AuroraApplication).provisioningAvailability.onMainScreenResumed(
        refreshAllowed = mayRefreshProvisioning,
    )
    refreshProvisioningExpiryMonitor()
}

internal fun AuroraActivity.pauseMainScreenObservers() {
    screenResumed = false
    provisioningExpiryMonitor.stop()
    ++statusObserverGeneration
    statusObserver?.invoke()
    statusObserver = null
    storageOperationObserver?.invoke()
    storageOperationObserver = null
}

internal fun AuroraActivity.destroyMainScreenResources() {
    vpnServiceRequestHandler.removeCallbacks(reconcileVpnServiceRequest)
    provisioningExpiryMonitor.stop()
    worker.shutdownNow()
        .filterIsInstance<ProvisioningStorageCommand>()
        .forEach(ProvisioningStorageCommand::discardIfQueued)
    removeProvisioningDialog?.dismiss()
    synchronized(importInputLock) {
        pendingImport?.fill('\u0000')
        pendingImport = null
    }
}
