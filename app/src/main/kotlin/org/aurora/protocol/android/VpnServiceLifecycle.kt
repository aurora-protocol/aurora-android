package org.aurora.protocol.android

import java.util.concurrent.atomic.AtomicBoolean

/** A service-instance lease over the process-scoped VPN connection and teardown owner. */
internal class VpnServiceLifecycle internal constructor(
    private val processLifecycle: VpnProcessLifecycle,
    private val leaseId: Long,
) {
    private val released = AtomicBoolean()

    val teardownInProgress: Boolean
        get() = processLifecycle.teardownInProgress

    fun start(serviceStartId: Int): VpnConnectionStart {
        return if (released.get()) {
            VpnConnectionStart.Rejected
        } else {
            processLifecycle.start(leaseId, serviceStartId)
        }
    }

    fun shareActiveStart(serviceStartId: Int): Boolean {
        return !released.get() && processLifecycle.shareActiveStart(leaseId, serviceStartId)
    }

    fun attachSession(connectionGeneration: Long, session: AutoCloseable): Boolean {
        return !released.get() && processLifecycle.attachSession(leaseId, connectionGeneration, session)
    }

    fun promoteRuntime(
        connectionGeneration: Long,
        session: AutoCloseable,
        runtime: AutoCloseable,
    ): Boolean {
        return !released.get() &&
            processLifecycle.promoteRuntime(leaseId, connectionGeneration, session, runtime)
    }

    /** Marks the one-shot reservation unavailable; returns whether establishment may continue. */
    fun markProvisioningRequired(connectionGeneration: Long): Boolean {
        return processLifecycle.markProvisioningUnavailable(
            leaseId,
            connectionGeneration,
            TunnelStatus.PROVISIONING_REQUIRED,
        )
    }

    /** Marks an expired reservation retained in storage; returns whether establishment may continue. */
    fun markProvisioningExpired(connectionGeneration: Long): Boolean {
        return processLifecycle.markProvisioningUnavailable(
            leaseId,
            connectionGeneration,
            TunnelStatus.PROVISIONING_EXPIRED,
        )
    }

    fun beginConnectionWork(connectionGeneration: Long): Boolean {
        return processLifecycle.beginConnectionWork(leaseId, connectionGeneration)
    }

    fun discardConnectionWork(connectionGeneration: Long) {
        processLifecycle.discardConnectionWork(leaseId, connectionGeneration)
    }

    fun finishConnectionWork(connectionGeneration: Long) {
        processLifecycle.finishConnectionWork(leaseId, connectionGeneration)
    }

    fun stop(
        expectedGeneration: Long? = null,
        serviceStartId: Int? = null,
        failed: Boolean = false,
    ): VpnConnectionStop {
        return if (released.get()) {
            VpnConnectionStop.Ignored
        } else {
            processLifecycle.stop(leaseId, expectedGeneration, serviceStartId, failed)
        }
    }

    fun finishLifecycleStop(teardownId: Long) {
        processLifecycle.finishLifecycleStop(leaseId, teardownId)
    }

    /** Releases this service instance without interrupting process-owned close work. */
    fun release() {
        if (released.compareAndSet(false, true)) {
            processLifecycle.release(leaseId)
        }
    }
}
