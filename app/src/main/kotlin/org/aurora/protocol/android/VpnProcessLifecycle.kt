package org.aurora.protocol.android

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

/**
 * Owns VPN generations and resource teardown for the lifetime of the application process.
 *
 * Android can destroy and recreate a [android.net.VpnService] object while close calls from the
 * old object are still running. Instance leases prevent stale callbacks from taking ownership,
 * while this shared owner rejects every new lease until detached resource cleanup completes.
 */
internal class VpnProcessLifecycle(
    internal val onTeardownFailure: (Throwable) -> Unit,
    initialTeardownExecutor: ExecutorService = newVpnTeardownExecutor(),
    internal val teardownExecutorFactory: () -> ExecutorService = ::newVpnTeardownExecutor,
    internal val rejectionExecutor: Executor = newVpnRejectionExecutor(),
    internal val tunnelStatus: VpnTunnelStatus = VpnTunnelStatus(),
) {
    internal val lock = Any()
    internal var teardownExecutor = initialTeardownExecutor
    internal var leaseSequence = 0L
    internal var generation = 0L
    internal var teardownSequence = 0L
    internal val activeLeases = mutableSetOf<Long>()
    internal var activeConnection: ActiveConnection? = null
    internal var activeTeardown: ActiveTeardown? = null

    val teardownInProgress: Boolean
        get() = synchronized(lock) { activeTeardown != null }

    fun acquire(): VpnServiceLifecycle = synchronized(lock) {
        val leaseId = ++leaseSequence
        activeLeases += leaseId
        VpnServiceLifecycle(this, leaseId)
    }

    /** Test-only process teardown; production ownership intentionally survives service instances. */
    internal fun shutdownForTest() = synchronized(lock) {
        teardownExecutor.shutdown()
    }
}
