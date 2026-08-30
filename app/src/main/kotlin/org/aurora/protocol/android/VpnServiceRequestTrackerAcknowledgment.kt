package org.aurora.protocol.android

internal fun VpnServiceRequestTracker.clearIfAcknowledged(currentStatus: TunnelStatusPublication): Boolean {
    val request = pending ?: return false
    if (!request.isAcknowledgedBy(currentStatus)) {
        return false
    }
    pending = null
    return true
}

internal fun VpnServiceRequestTracker.expireIfUnacknowledged(
    currentStatus: TunnelStatusPublication,
    currentUptimeMillis: Long,
): PendingVpnServiceCommand? {
    val request = pending ?: return null
    if (request.isAcknowledgedBy(currentStatus)) {
        pending = null
        return null
    }
    if (currentUptimeMillis < request.timeoutAtUptimeMillis) {
        return null
    }
    pending = null
    return request
}

internal fun PendingVpnServiceCommand.isAcknowledgedBy(currentStatus: TunnelStatusPublication): Boolean {
    if (currentStatus.revision <= afterStatusRevision) {
        return false
    }
    return command == VpnServiceCommand.CONNECT ||
        currentStatus.status == TunnelStatus.DISCONNECTING ||
        currentStatus.status == TunnelStatus.IDLE ||
        currentStatus.status == TunnelStatus.FAILED ||
        currentStatus.status == TunnelStatus.PROVISIONING_REQUIRED ||
        currentStatus.status == TunnelStatus.PROVISIONING_EXPIRED ||
        currentStatus.status == TunnelStatus.FAILED_REQUIRES_PROVISIONING
}
