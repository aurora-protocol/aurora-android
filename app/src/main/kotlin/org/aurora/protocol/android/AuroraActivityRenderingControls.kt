package org.aurora.protocol.android

internal fun AuroraActivity.refreshControls() {
    val controls = currentControls()
    val actionCopies = mainScreenActionCopies(
        importInProgress = requestState.importInProgress,
        storageOperation = provisioningStorageOperations.publication.operation,
        connectRequested = requestState.connectRequested,
        pendingVpnServiceCommand = vpnServiceRequestTracker.pending?.command,
        tunnelStatus = vpnTunnelStatus.status,
    )
    if (removeProvisioningDialog?.isShowing == true && !controls.removeProvisioningEnabled) {
        removeProvisioningDialog?.dismiss()
    }
    importField.isEnabled = controls.importInputEnabled
    importButton.isEnabled = controls.importEnabled
    importButton.setText(mainScreenActionCopyResource(actionCopies.importAction))
    applyAccessibilityHint(importButton, getString(mainScreenActionHintResource(actionCopies.importAction)))
    removeProvisioningButton.isEnabled = controls.removeProvisioningEnabled
    removeProvisioningButton.setText(mainScreenActionCopyResource(actionCopies.removeAction))
    applyAccessibilityHint(
        removeProvisioningButton,
        getString(mainScreenActionHintResource(actionCopies.removeAction)),
    )
    connectButton.isEnabled = controls.connectEnabled
    connectButton.setText(mainScreenActionCopyResource(actionCopies.connectAction))
    applyAccessibilityHint(connectButton, getString(mainScreenActionHintResource(actionCopies.connectAction)))
    disconnectButton.isEnabled = controls.disconnectEnabled
    disconnectButton.setText(mainScreenActionCopyResource(actionCopies.disconnectAction))
    applyAccessibilityHint(
        disconnectButton,
        getString(mainScreenActionHintResource(actionCopies.disconnectAction)),
    )
    progressIndicator.visibility = if (controls.showProgress) android.view.View.VISIBLE else android.view.View.GONE
    refreshProvisioningExpiryMonitor()
}

internal fun AuroraActivity.refreshProvisioningExpiryMonitor() {
    val tunnelStatus = vpnTunnelStatus.status
    val monitorAllowed = screenResumed &&
        provisioningRefreshAllowed(
            importInProgress = requestState.importInProgress,
            storageOperationInProgress = storageOperationInProgress,
            connectRequested = requestState.connectRequested,
            pendingVpnServiceCommand = vpnServiceRequestTracker.pending?.command,
        ) &&
        (tunnelStatus == TunnelStatus.IDLE || tunnelStatus == TunnelStatus.FAILED)
    val expiryUnix = if (monitorAllowed) {
        (application as AuroraApplication).provisioningAvailability.knownReservationExpiryUnix
    } else {
        null
    }
    provisioningExpiryMonitor.update(expiryUnix)
}

internal fun AuroraActivity.currentControls(): MainScreenControls = mainScreenControls(
    importInProgress = requestState.importInProgress,
    storageOperationInProgress = storageOperationInProgress,
    connectRequested = requestState.connectRequested,
    pendingVpnServiceCommand = vpnServiceRequestTracker.pending?.command,
    hasProvisioningInput = hasProvisioningInput(importField.text),
    tunnelStatus = vpnTunnelStatus.status,
)

internal val AuroraActivity.storageOperationInProgress: Boolean
    get() = provisioningStorageOperations.publication.operation != null

internal val AuroraActivity.provisioningStorageOperations: ProvisioningStorageOperations
    get() = (application as AuroraApplication).provisioningStorageOperations
