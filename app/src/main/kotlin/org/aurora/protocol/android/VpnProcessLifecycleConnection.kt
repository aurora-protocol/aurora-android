package org.aurora.protocol.android

internal fun VpnProcessLifecycle.start(leaseId: Long, serviceStartId: Int): VpnConnectionStart = synchronized(lock) {
    if (leaseId !in activeLeases || activeTeardown != null) {
        return VpnConnectionStart.Rejected
    }
    activeConnection?.let { connection ->
        if (connection.leaseId != leaseId) {
            return VpnConnectionStart.Rejected
        }
        connection.serviceStartId = serviceStartId
        return VpnConnectionStart.Shared
    }
    val ownGeneration = ++generation
    activeConnection = ActiveConnection(
        leaseId = leaseId,
        generation = ownGeneration,
        serviceStartId = serviceStartId,
    )
    tunnelStatus.publish(TunnelStatus.CONNECTING)
    VpnConnectionStart.Accepted(ownGeneration)
}

/** Adopts a newer Android start ID without authorizing a new VPN connection. */
internal fun VpnProcessLifecycle.shareActiveStart(leaseId: Long, serviceStartId: Int): Boolean = synchronized(lock) {
    val connection = activeConnection
    if (leaseId !in activeLeases || connection == null || connection.leaseId != leaseId) {
        false
    } else {
        connection.serviceStartId = serviceStartId
        true
    }
}

internal fun VpnProcessLifecycle.attachSession(
    leaseId: Long,
    connectionGeneration: Long,
    session: AutoCloseable,
): Boolean = synchronized(lock) {
    val connection = activeConnection
    if (leaseId !in activeLeases ||
        connection == null ||
        connection.leaseId != leaseId ||
        connection.generation != connectionGeneration ||
        !connection.connectionWorkStarted ||
        connection.connectionWorkComplete ||
        connection.session != null ||
        connection.runtime != null
    ) {
        false
    } else {
        connection.session = session
        true
    }
}
