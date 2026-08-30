package org.aurora.protocol.android

import android.os.Bundle

internal data class MainScreenLifecycleRestore(
    val requestState: ConnectionRequestState,
    val vpnServiceRequestTracker: VpnServiceRequestTracker,
    val initialTunnelStatus: TunnelStatusPublication,
    val initialStorageOperation: ProvisioningStorageOperationPublication,
)

internal fun AuroraActivity.restoreMainScreenLifecycle(savedInstanceState: Bundle?): MainScreenLifecycleRestore {
    val requestState = ConnectionRequestState(
        restoreConnectionRequest(
            requested = savedInstanceState?.getBoolean(AuroraActivity.savedConnectionRequest) == true,
            restoredProcessSessionId = savedInstanceState?.getString(AuroraActivity.savedConnectionRequestProcessSession),
            currentProcessSessionId = vpnServiceProcessSessionId,
        ),
    )
    val initialTunnelStatus = vpnTunnelStatus.publication
    val initialStorageOperation = provisioningStorageOperations.publication
    val restoredCommand = savedInstanceState?.getString(AuroraActivity.savedVpnServiceCommand)?.let { name ->
        enumValues<VpnServiceCommand>().firstOrNull { it.name == name }
    }
    val vpnServiceRequestTracker = VpnServiceRequestTracker(
        restoredCommand = restoredCommand,
        restoredAfterStatusRevision = if (savedInstanceState?.containsKey(AuroraActivity.savedVpnServiceCommandRevision) == true) {
            savedInstanceState.getLong(AuroraActivity.savedVpnServiceCommandRevision)
        } else {
            null
        },
        restoredTimeoutAtUptimeMillis = if (
            savedInstanceState?.containsKey(AuroraActivity.savedVpnServiceCommandTimeout) == true
        ) {
            savedInstanceState.getLong(AuroraActivity.savedVpnServiceCommandTimeout)
        } else {
            null
        },
        restoredConnectRequestId = if (
            savedInstanceState?.containsKey(AuroraActivity.savedVpnServiceConnectRequestId) == true
        ) {
            savedInstanceState.getLong(AuroraActivity.savedVpnServiceConnectRequestId)
        } else {
            null
        },
        restoredProcessSessionId = savedInstanceState?.getString(AuroraActivity.savedVpnServiceProcessSession),
        currentStatusRevision = initialTunnelStatus.revision,
    )
    return MainScreenLifecycleRestore(
        requestState = requestState,
        vpnServiceRequestTracker = vpnServiceRequestTracker,
        initialTunnelStatus = initialTunnelStatus,
        initialStorageOperation = initialStorageOperation,
    )
}
