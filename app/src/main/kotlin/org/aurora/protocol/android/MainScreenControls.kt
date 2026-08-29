package org.aurora.protocol.android

internal data class MainScreenControls(
    val importInputEnabled: Boolean,
    val importEnabled: Boolean,
    val removeProvisioningEnabled: Boolean,
    val connectEnabled: Boolean,
    val disconnectEnabled: Boolean,
    val showProgress: Boolean,
)

internal fun mainScreenControls(
    importInProgress: Boolean,
    storageOperationInProgress: Boolean,
    connectRequested: Boolean,
    pendingVpnServiceCommand: VpnServiceCommand?,
    hasProvisioningInput: Boolean,
    tunnelStatus: TunnelStatus,
): MainScreenControls {
    val connectPending = connectRequested || pendingVpnServiceCommand == VpnServiceCommand.CONNECT
    val disconnectPending = pendingVpnServiceCommand == VpnServiceCommand.DISCONNECT
    val busy = importInProgress || storageOperationInProgress || connectPending || disconnectPending
    val tunnelBusy = tunnelStatus == TunnelStatus.CONNECTING ||
        tunnelStatus == TunnelStatus.CONNECTED ||
        tunnelStatus == TunnelStatus.DISCONNECTING
    val provisioningRequired = tunnelStatus == TunnelStatus.PROVISIONING_REQUIRED ||
        tunnelStatus == TunnelStatus.FAILED_REQUIRES_PROVISIONING
    return MainScreenControls(
        importInputEnabled = !busy && !tunnelBusy,
        importEnabled = !busy && !tunnelBusy && hasProvisioningInput,
        removeProvisioningEnabled = !busy && !tunnelBusy && !provisioningRequired,
        connectEnabled = !busy && !tunnelBusy && !provisioningRequired,
        disconnectEnabled = !importInProgress && !storageOperationInProgress && !disconnectPending &&
            (connectPending || tunnelStatus == TunnelStatus.CONNECTING || tunnelStatus == TunnelStatus.CONNECTED),
        showProgress = busy || tunnelStatus == TunnelStatus.CONNECTING || tunnelStatus == TunnelStatus.DISCONNECTING,
    )
}
