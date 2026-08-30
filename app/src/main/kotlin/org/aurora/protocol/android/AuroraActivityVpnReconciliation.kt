package org.aurora.protocol.android

import android.os.SystemClock

internal fun AuroraActivity.reconcilePendingVpnServiceRequest() {
    val currentStatus = vpnTunnelStatus.publication
    if (vpnServiceRequestTracker.clearIfAcknowledged(currentStatus)) {
        schedulePendingVpnServiceReconciliation()
        refreshControls()
        return
    }
    val expiredRequest = vpnServiceRequestTracker.expireIfUnacknowledged(
        currentStatus = currentStatus,
        currentUptimeMillis = SystemClock.uptimeMillis(),
    )
    if (expiredRequest == null) {
        schedulePendingVpnServiceReconciliation()
        return
    }
    expiredRequest.connectRequestId?.let(vpnConnectRequestGate::invalidate)
    schedulePendingVpnServiceReconciliation()
    showLocalStatus(
        if (expiredRequest.command == VpnServiceCommand.CONNECT) {
            R.string.status_connection_unconfirmed
        } else {
            R.string.status_disconnect_unconfirmed
        },
    )
    refreshControls()
}

internal fun AuroraActivity.schedulePendingVpnServiceReconciliation() {
    vpnServiceRequestHandler.removeCallbacks(reconcileVpnServiceRequest)
    val timeoutAt = vpnServiceRequestTracker.pending?.timeoutAtUptimeMillis ?: return
    vpnServiceRequestHandler.postDelayed(
        reconcileVpnServiceRequest,
        maxOf(0L, timeoutAt - SystemClock.uptimeMillis()),
    )
}
