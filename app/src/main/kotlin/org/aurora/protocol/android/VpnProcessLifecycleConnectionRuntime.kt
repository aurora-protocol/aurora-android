package org.aurora.protocol.android

internal fun VpnProcessLifecycle.promoteRuntime(
    leaseId: Long,
    connectionGeneration: Long,
    session: AutoCloseable,
    runtime: AutoCloseable,
): Boolean = synchronized(lock) {
    val connection = activeConnection
    if (leaseId !in activeLeases ||
        connection == null ||
        connection.leaseId != leaseId ||
        connection.generation != connectionGeneration ||
        !connection.connectionWorkStarted ||
        connection.connectionWorkComplete ||
        connection.session !== session ||
        connection.runtime != null
    ) {
        false
    } else {
        connection.session = null
        connection.runtime = runtime
        tunnelStatus.publish(TunnelStatus.CONNECTED)
        true
    }
}

internal fun VpnProcessLifecycle.markProvisioningUnavailable(
    leaseId: Long,
    connectionGeneration: Long,
    unavailableStatus: TunnelStatus,
): Boolean = synchronized(lock) {
    require(
        unavailableStatus == TunnelStatus.PROVISIONING_REQUIRED ||
            unavailableStatus == TunnelStatus.PROVISIONING_EXPIRED,
    ) { "invalid provisioning terminal status" }
    activeConnection?.takeIf {
        it.leaseId == leaseId && it.generation == connectionGeneration
    }?.let { connection ->
        connection.provisioningTerminalStatus = unavailableStatus
        return true
    }
    activeTeardown?.takeIf {
        it.leaseId == leaseId && it.connectionGeneration == connectionGeneration
    }?.let { teardown ->
        teardown.terminalStatus = teardown.terminalStatus.withProvisioningUnavailable(unavailableStatus)
    }
    false
}
