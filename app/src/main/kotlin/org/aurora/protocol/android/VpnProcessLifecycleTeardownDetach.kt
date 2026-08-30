package org.aurora.protocol.android

internal fun VpnProcessLifecycle.detachConnection(
    connection: ActiveConnection,
    lifecycleComplete: Boolean,
    terminalStatus: TunnelStatus,
): ActiveTeardown {
    synchronized(lock) {
        check(activeConnection === connection && activeTeardown == null)
        activeConnection = null
        ++generation
        val resource = connection.runtime ?: connection.session
        val teardown = ActiveTeardown(
            id = ++teardownSequence,
            leaseId = connection.leaseId,
            connectionGeneration = connection.generation,
            connectionWorkStarted = connection.connectionWorkStarted,
            connectionWorkComplete = connection.connectionWorkComplete,
            lifecycleComplete = lifecycleComplete,
            terminalStatus = terminalStatus,
        )
        activeTeardown = teardown
        submitTeardown(teardown, resource)
        return teardown
    }
}
