package org.aurora.protocol.android

import android.os.SystemClock
import org.aurora.protocol.android.core.AuroraLog

internal fun AuroraActivity.startConnection() {
    val requestId = vpnConnectRequestGate.issue()
    vpnServiceRequestTracker.begin(
        VpnServiceCommand.CONNECT,
        vpnTunnelStatus.publication.revision,
        SystemClock.uptimeMillis(),
        connectRequestId = requestId,
    )
    schedulePendingVpnServiceReconciliation()
    refreshControls()
    val failure = runVpnServiceRequest { AuroraVpnService.connect(this, requestId) }
    if (failure == null) {
        showLocalStatus(R.string.status_connecting)
    } else {
        vpnConnectRequestGate.invalidate(requestId)
        vpnServiceRequestTracker.clear()
        schedulePendingVpnServiceReconciliation()
        AuroraLog.debug("VPN service start", failure)
        showLocalStatus(R.string.status_connection_failed)
    }
    refreshControls()
}
