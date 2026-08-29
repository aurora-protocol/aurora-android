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
    hasProvisioningInput: Boolean,
    tunnelStatus: TunnelStatus,
): MainScreenControls {
    val busy = importInProgress || storageOperationInProgress || connectRequested
    val tunnelBusy = tunnelStatus == TunnelStatus.CONNECTING ||
        tunnelStatus == TunnelStatus.CONNECTED ||
        tunnelStatus == TunnelStatus.DISCONNECTING
    return MainScreenControls(
        importInputEnabled = !busy && !tunnelBusy,
        importEnabled = !busy && !tunnelBusy && hasProvisioningInput,
        removeProvisioningEnabled = !busy && !tunnelBusy,
        connectEnabled = !busy && !tunnelBusy,
        disconnectEnabled = !importInProgress && !storageOperationInProgress &&
            (connectRequested || tunnelStatus == TunnelStatus.CONNECTING || tunnelStatus == TunnelStatus.CONNECTED),
        showProgress = busy || tunnelStatus == TunnelStatus.CONNECTING || tunnelStatus == TunnelStatus.DISCONNECTING,
    )
}
