package org.aurora.protocol.android

/** UI-side ordering gate for revisioned status callbacks and newer local feedback. */
internal class TunnelStatusRenderState(initial: TunnelStatusPublication) {
    private var handledRevision = initial.revision

    fun markLocalFeedback(current: TunnelStatusPublication) {
        handledRevision = maxOf(handledRevision, current.revision)
    }

    fun consumeIfCurrent(
        candidate: TunnelStatusPublication,
        current: TunnelStatusPublication,
    ): Boolean {
        if (candidate != current || candidate.revision <= handledRevision) {
            return false
        }
        handledRevision = candidate.revision
        return true
    }
}

internal fun tunnelStatusText(status: TunnelStatus): Int = when (status) {
    TunnelStatus.IDLE -> R.string.status_ready
    TunnelStatus.CHECKING_PROVISIONING -> R.string.status_checking_provisioning
    TunnelStatus.CONNECTING -> R.string.status_connecting
    TunnelStatus.CONNECTED -> R.string.status_connected
    TunnelStatus.DISCONNECTING -> R.string.status_disconnecting
    TunnelStatus.FAILED -> R.string.status_tunnel_failed
    TunnelStatus.PROVISIONING_REQUIRED -> R.string.status_provisioning_required
    TunnelStatus.PROVISIONING_EXPIRED -> R.string.status_provisioning_expired
    TunnelStatus.FAILED_REQUIRES_PROVISIONING -> R.string.status_tunnel_failed_reimport
}
