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
    val tunnelActive = tunnelStatus == TunnelStatus.CONNECTING || tunnelStatus == TunnelStatus.CONNECTED
    return MainScreenControls(
        importInputEnabled = !busy && !tunnelActive,
        importEnabled = !busy && !tunnelActive && hasProvisioningInput,
        connectEnabled = !busy && !tunnelActive,
        disconnectEnabled = !importInProgress && (connectRequested || tunnelActive),
        showProgress = busy || tunnelStatus == TunnelStatus.CONNECTING,
    )
}
