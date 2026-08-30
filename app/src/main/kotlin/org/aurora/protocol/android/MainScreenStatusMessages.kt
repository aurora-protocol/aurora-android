package org.aurora.protocol.android

internal fun mainScreenInitialStatusMessage(
    connectRequested: Boolean,
    pendingVpnServiceCommand: VpnServiceCommand?,
    initialStorageOperation: ProvisioningStorageOperation?,
    initialTunnelStatus: TunnelStatus,
): Int = when {
    connectRequested -> R.string.status_preparing_connection
    pendingVpnServiceCommand == VpnServiceCommand.CONNECT -> R.string.status_connecting
    pendingVpnServiceCommand == VpnServiceCommand.DISCONNECT -> R.string.status_disconnect_requested
    initialStorageOperation == ProvisioningStorageOperation.IMPORTING -> R.string.status_importing
    initialStorageOperation == ProvisioningStorageOperation.REMOVING -> R.string.status_removing_provisioning
    else -> tunnelStatusText(initialTunnelStatus)
}

internal fun mainScreenIdleStatusMessage(
    connectRequested: Boolean,
    pendingVpnServiceCommand: VpnServiceCommand?,
    tunnelStatus: TunnelStatus,
): Int = when {
    connectRequested -> R.string.status_preparing_connection
    pendingVpnServiceCommand == VpnServiceCommand.CONNECT -> R.string.status_connecting
    pendingVpnServiceCommand == VpnServiceCommand.DISCONNECT -> R.string.status_disconnect_requested
    else -> tunnelStatusText(tunnelStatus)
}
