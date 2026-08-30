package org.aurora.protocol.android

internal fun VpnProcessLifecycle.stop(
    leaseId: Long,
    expectedGeneration: Long?,
    serviceStartId: Int?,
    failed: Boolean,
): VpnConnectionStop = synchronized(lock) {
    if (leaseId !in activeLeases) {
        return VpnConnectionStop.Ignored
    }
    val connection = activeConnection
    if (expectedGeneration != null &&
        (connection?.leaseId != leaseId || connection.generation != expectedGeneration)
    ) {
        return VpnConnectionStop.Ignored
    }
    activeTeardown?.let {
        return VpnConnectionStop.AlreadyInProgress(serviceStartId)
    }
    if (connection == null || connection.leaseId != leaseId) {
        return if (serviceStartId == null) {
            VpnConnectionStop.Ignored
        } else {
            VpnConnectionStop.AlreadyInProgress(serviceStartId)
        }
    }

    val teardown = detachConnection(
        connection,
        lifecycleComplete = false,
        terminalStatus = terminalTunnelStatus(failed, connection.provisioningTerminalStatus),
    )
    tunnelStatus.publish(TunnelStatus.DISCONNECTING)
    VpnConnectionStop.Started(
        teardownId = teardown.id,
        serviceStartId = serviceStartId ?: connection.serviceStartId,
    )
}

internal fun VpnProcessLifecycle.finishLifecycleStop(leaseId: Long, teardownId: Long) = synchronized(lock) {
    activeTeardown?.takeIf { it.leaseId == leaseId && it.id == teardownId }?.let { teardown ->
        teardown.lifecycleComplete = true
        finishTeardownIfComplete(teardown)
    }
}

internal fun VpnProcessLifecycle.release(leaseId: Long) = synchronized(lock) {
    if (!activeLeases.remove(leaseId)) {
        return@synchronized
    }
    activeConnection?.takeIf { it.leaseId == leaseId }?.let { connection ->
        detachConnection(
            connection,
            lifecycleComplete = true,
            terminalStatus = terminalTunnelStatus(failed = false, connection.provisioningTerminalStatus),
        )
        tunnelStatus.publish(TunnelStatus.DISCONNECTING)
    }
}
