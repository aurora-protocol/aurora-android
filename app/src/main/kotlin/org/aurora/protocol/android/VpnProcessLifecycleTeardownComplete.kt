package org.aurora.protocol.android

import org.aurora.protocol.android.core.AuroraLog

internal fun VpnProcessLifecycle.completeTeardown(teardown: ActiveTeardown, resource: AutoCloseable?) {
    val failure = collectCleanupFailures({ resource?.close() })
    synchronized(lock) {
        if (activeTeardown === teardown) {
            teardown.resourceCleanupComplete = true
            finishTeardownIfComplete(teardown)
        }
    }
    failure?.let(::reportVpnLifecycleFailure)
}

internal fun VpnProcessLifecycle.reportVpnLifecycleFailure(error: Throwable) {
    try {
        onTeardownFailure(error)
    } catch (error: Exception) {
        AuroraLog.debug("teardown failure reporting", error)
    }
}

internal fun VpnProcessLifecycle.finishTeardownIfComplete(teardown: ActiveTeardown) {
    if (activeTeardown === teardown &&
        teardown.lifecycleComplete &&
        teardown.resourceCleanupComplete &&
        teardown.connectionWorkComplete
    ) {
        activeTeardown = null
        tunnelStatus.publish(teardown.terminalStatus)
    }
}
