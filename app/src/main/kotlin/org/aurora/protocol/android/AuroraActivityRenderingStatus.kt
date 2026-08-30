package org.aurora.protocol.android

internal fun AuroraActivity.showLocalStatus(message: Int) {
    tunnelStatusRenderState.markLocalFeedback(vpnTunnelStatus.publication)
    presentStatus(message)
}

internal fun AuroraActivity.presentStatus(message: Int) {
    applyStatusPresentation(status, statusLabelText, getString(message))
}

internal fun AuroraActivity.renderStorageOperation(update: ProvisioningStorageOperationPublication) {
    val previousOperation = lastRenderedStorageOperation
    lastRenderedStorageOperation = update.operation
    when (update.operation) {
        ProvisioningStorageOperation.IMPORTING -> showLocalStatus(R.string.status_importing)
        ProvisioningStorageOperation.REMOVING -> showLocalStatus(R.string.status_removing_provisioning)
        null -> if (storageIdleRestoresTunnelStatus(previousOperation)) {
            showLocalStatus(
                mainScreenIdleStatusMessage(
                    connectRequested = requestState.connectRequested,
                    pendingVpnServiceCommand = vpnServiceRequestTracker.pending?.command,
                    tunnelStatus = vpnTunnelStatus.status,
                ),
            )
        }
    }
    refreshControls()
}

internal fun AuroraActivity.renderTunnelStatus(update: TunnelStatusPublication) {
    if (!tunnelStatusRenderState.consumeIfCurrent(update, vpnTunnelStatus.publication)) {
        return
    }
    if (vpnServiceRequestTracker.clearIfAcknowledged(update)) {
        schedulePendingVpnServiceReconciliation()
    }
    status.setText(tunnelStatusText(update.status))
    applyStatusPresentation(status, statusLabelText, status.text)
    refreshControls()
}
