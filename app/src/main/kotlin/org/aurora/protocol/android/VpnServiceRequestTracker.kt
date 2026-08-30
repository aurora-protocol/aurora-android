package org.aurora.protocol.android

/** Tracks UI-dispatched service work until a newer authoritative status arrives. */
internal class VpnServiceRequestTracker(
    restoredCommand: VpnServiceCommand? = null,
    restoredAfterStatusRevision: Long? = null,
    restoredTimeoutAtUptimeMillis: Long? = null,
    restoredConnectRequestId: Long? = null,
    restoredProcessSessionId: String? = null,
    currentStatusRevision: Long = 0,
    currentProcessSessionId: String = vpnServiceProcessSessionId,
) {
    var pending: PendingVpnServiceCommand? = if (
        restoredCommand != null &&
        restoredAfterStatusRevision == currentStatusRevision &&
        restoredTimeoutAtUptimeMillis != null &&
        hasValidConnectRequestId(restoredCommand, restoredConnectRequestId) &&
        restoredProcessSessionId == currentProcessSessionId
    ) {
        PendingVpnServiceCommand(
            restoredCommand,
            restoredAfterStatusRevision,
            restoredTimeoutAtUptimeMillis,
            restoredConnectRequestId,
        )
    } else {
        null
    }
        internal set

    fun begin(
        command: VpnServiceCommand,
        currentStatusRevision: Long,
        currentUptimeMillis: Long,
        connectRequestId: Long?,
    ) {
        require(hasValidConnectRequestId(command, connectRequestId))
        pending = PendingVpnServiceCommand(
            command = command,
            afterStatusRevision = currentStatusRevision,
            timeoutAtUptimeMillis = currentUptimeMillis + vpnServiceRequestTimeoutMillis,
            connectRequestId = connectRequestId,
        )
    }

    fun clear() {
        pending = null
    }
}
