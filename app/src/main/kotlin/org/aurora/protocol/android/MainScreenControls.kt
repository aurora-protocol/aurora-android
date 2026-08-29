package org.aurora.protocol.android

internal data class MainScreenControls(
    val importInputEnabled: Boolean,
    val importEnabled: Boolean,
    val connectEnabled: Boolean,
    val disconnectEnabled: Boolean,
    val showProgress: Boolean,
)

internal fun mainScreenControls(
    importInProgress: Boolean,
    connectRequested: Boolean,
    hasProvisioningInput: Boolean,
    tunnelStatus: TunnelStatus,
): MainScreenControls {
    val busy = importInProgress || connectRequested
    val tunnelBusy = tunnelStatus == TunnelStatus.CONNECTING ||
        tunnelStatus == TunnelStatus.CONNECTED ||
        tunnelStatus == TunnelStatus.DISCONNECTING
    return MainScreenControls(
        importInputEnabled = !busy && !tunnelBusy,
        importEnabled = !busy && !tunnelBusy && hasProvisioningInput,
        connectEnabled = !busy && !tunnelBusy,
        disconnectEnabled = !importInProgress &&
            (connectRequested || tunnelStatus == TunnelStatus.CONNECTING || tunnelStatus == TunnelStatus.CONNECTED),
        showProgress = busy || tunnelStatus == TunnelStatus.CONNECTING || tunnelStatus == TunnelStatus.DISCONNECTING,
    )
}
