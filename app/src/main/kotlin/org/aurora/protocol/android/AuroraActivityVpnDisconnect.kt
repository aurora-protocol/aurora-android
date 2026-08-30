package org.aurora.protocol.android

import android.os.SystemClock
import org.aurora.protocol.android.core.AuroraLog

internal fun AuroraActivity.disconnect() {
    if (!currentControls().disconnectEnabled) {
        return
    }
    val currentStatus = vpnTunnelStatus.publication
    val pendingConnectRequestId = vpnServiceRequestTracker.pending
        ?.takeIf { it.command == VpnServiceCommand.CONNECT }
        ?.connectRequestId
    requestState.cancelConnectionRequest()
    pendingConnectRequestId?.let(vpnConnectRequestGate::invalidate)
    if (vpnServiceRequestTracker.clearIfAcknowledged(currentStatus)) {
        schedulePendingVpnServiceReconciliation()
    }

    val connectPending = vpnServiceRequestTracker.pending?.command == VpnServiceCommand.CONNECT
    val tunnelActive = currentStatus.status == TunnelStatus.CONNECTING ||
        currentStatus.status == TunnelStatus.CONNECTED
    if (!connectPending && !tunnelActive) {
        showLocalStatus(tunnelStatusText(currentStatus.status))
        refreshControls()
        return
    }

    vpnServiceRequestTracker.begin(
        VpnServiceCommand.DISCONNECT,
        currentStatus.revision,
        SystemClock.uptimeMillis(),
        connectRequestId = null,
    )
    schedulePendingVpnServiceReconciliation()
    refreshControls()
    val failure = runVpnServiceRequest { AuroraVpnService.disconnect(this) }
    if (failure == null) {
        showLocalStatus(R.string.status_disconnect_requested)
    } else {
        vpnServiceRequestTracker.clear()
        schedulePendingVpnServiceReconciliation()
        AuroraLog.debug("VPN service stop", failure)
        showLocalStatus(R.string.status_disconnect_failed)
    }
    refreshControls()
}
