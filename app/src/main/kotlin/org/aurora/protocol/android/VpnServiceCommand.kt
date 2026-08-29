package org.aurora.protocol.android

import java.util.UUID

internal const val connectVpnAction = "org.aurora.protocol.android.action.CONNECT"
internal const val disconnectVpnAction = "org.aurora.protocol.android.action.DISCONNECT"
internal val vpnServiceProcessSessionId: String = UUID.randomUUID().toString()

internal enum class VpnServiceCommand {
    CONNECT,
    DISCONNECT,
}

internal data class PendingVpnServiceCommand(
    val command: VpnServiceCommand,
    val afterStatusRevision: Long,
)

/** Tracks UI-dispatched service work until a newer authoritative status arrives. */
internal class VpnServiceRequestTracker(
    restoredCommand: VpnServiceCommand? = null,
    restoredAfterStatusRevision: Long? = null,
    restoredProcessSessionId: String? = null,
    currentStatusRevision: Long = 0,
    currentProcessSessionId: String = vpnServiceProcessSessionId,
) {
    var pending: PendingVpnServiceCommand? = if (
        restoredCommand != null &&
        restoredAfterStatusRevision == currentStatusRevision &&
        restoredProcessSessionId == currentProcessSessionId
    ) {
        PendingVpnServiceCommand(restoredCommand, restoredAfterStatusRevision)
    } else {
        null
    }
        private set

    fun begin(command: VpnServiceCommand, currentStatusRevision: Long) {
        pending = PendingVpnServiceCommand(command, currentStatusRevision)
    }

    fun clear() {
        pending = null
    }

    fun clearIfSuperseded(currentStatusRevision: Long): Boolean {
        val request = pending ?: return false
        if (currentStatusRevision <= request.afterStatusRevision) {
            return false
        }
        pending = null
        return true
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
