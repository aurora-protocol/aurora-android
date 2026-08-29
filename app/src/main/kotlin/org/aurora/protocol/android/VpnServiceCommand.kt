package org.aurora.protocol.android

import java.util.UUID

internal const val connectVpnAction = "org.aurora.protocol.android.action.CONNECT"
internal const val disconnectVpnAction = "org.aurora.protocol.android.action.DISCONNECT"
internal const val vpnServiceRequestTimeoutMillis = 10_000L
internal val vpnServiceProcessSessionId: String = UUID.randomUUID().toString()

internal enum class VpnServiceCommand {
    CONNECT,
    DISCONNECT,
}

internal data class PendingVpnServiceCommand(
    val command: VpnServiceCommand,
    val afterStatusRevision: Long,
    val timeoutAtUptimeMillis: Long,
)

/** Tracks UI-dispatched service work until a newer authoritative status arrives. */
internal class VpnServiceRequestTracker(
    restoredCommand: VpnServiceCommand? = null,
    restoredAfterStatusRevision: Long? = null,
    restoredTimeoutAtUptimeMillis: Long? = null,
    restoredProcessSessionId: String? = null,
    currentStatusRevision: Long = 0,
    currentProcessSessionId: String = vpnServiceProcessSessionId,
) {
    var pending: PendingVpnServiceCommand? = if (
        restoredCommand != null &&
        restoredAfterStatusRevision == currentStatusRevision &&
        restoredTimeoutAtUptimeMillis != null &&
        restoredProcessSessionId == currentProcessSessionId
    ) {
        PendingVpnServiceCommand(restoredCommand, restoredAfterStatusRevision, restoredTimeoutAtUptimeMillis)
    } else {
        null
    }
        private set

    fun begin(command: VpnServiceCommand, currentStatusRevision: Long, currentUptimeMillis: Long) {
        pending = PendingVpnServiceCommand(
            command = command,
            afterStatusRevision = currentStatusRevision,
            timeoutAtUptimeMillis = currentUptimeMillis + vpnServiceRequestTimeoutMillis,
        )
    }

    fun clear() {
        pending = null
    }

    fun clearIfAcknowledged(currentStatus: TunnelStatusPublication): Boolean {
        val request = pending ?: return false
        if (!request.isAcknowledgedBy(currentStatus)) {
            return false
        }
        pending = null
        return true
    }

    fun expireIfUnacknowledged(
        currentStatus: TunnelStatusPublication,
        currentUptimeMillis: Long,
    ): VpnServiceCommand? {
        val request = pending ?: return null
        if (request.isAcknowledgedBy(currentStatus)) {
            pending = null
            return null
        }
        if (currentUptimeMillis < request.timeoutAtUptimeMillis) {
            return null
        }
        pending = null
        return request.command
    }

    private fun PendingVpnServiceCommand.isAcknowledgedBy(currentStatus: TunnelStatusPublication): Boolean {
        if (currentStatus.revision <= afterStatusRevision) {
            return false
        }
        return command == VpnServiceCommand.CONNECT ||
            currentStatus.status == TunnelStatus.DISCONNECTING ||
            currentStatus.status == TunnelStatus.IDLE ||
            currentStatus.status == TunnelStatus.FAILED
    }
}

internal fun vpnServiceCommand(action: String?): VpnServiceCommand? = when (action) {
    connectVpnAction -> VpnServiceCommand.CONNECT
    disconnectVpnAction -> VpnServiceCommand.DISCONNECT
    else -> null
}

internal fun runVpnServiceRequest(request: () -> Unit): RuntimeException? = try {
    request()
    null
} catch (error: RuntimeException) {
    error
}

internal fun collectCleanupFailures(vararg steps: () -> Unit): Throwable? {
    var failure: Throwable? = null
    steps.forEach { step ->
        try {
            step()
        } catch (error: Throwable) {
            val first = failure
            if (first == null) {
                failure = error
            } else if (first !== error) {
                first.addSuppressed(error)
            }
        }
    }
    return failure
}
