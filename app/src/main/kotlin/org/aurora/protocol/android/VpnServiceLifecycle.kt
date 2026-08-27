package org.aurora.protocol.android

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface VpnConnectionStart {
    data class Accepted(val generation: Long) : VpnConnectionStart

    data object Shared : VpnConnectionStart

    data object Rejected : VpnConnectionStart
}

internal sealed interface VpnConnectionStop {
    data class Started(
        val teardownId: Long,
        val serviceStartId: Int?,
    ) : VpnConnectionStop

    data class AlreadyInProgress(val serviceStartId: Int?) : VpnConnectionStop

    data object Ignored : VpnConnectionStop
}

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
    ): VpnConnectionStop {
        return if (released.get()) {
            VpnConnectionStop.Ignored
        } else {
            processLifecycle.stop(leaseId, expectedGeneration, serviceStartId)
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

/**
 * Owns VPN generations and resource teardown for the lifetime of the application process.
 *
 * Android can destroy and recreate a [android.net.VpnService] object while close calls from the
 * old object are still running. Instance leases prevent stale callbacks from taking ownership,
 * while this shared owner rejects every new lease until detached resource cleanup completes.
 */
internal class VpnProcessLifecycle(
    private val onTeardownFailure: (Throwable) -> Unit,
    initialTeardownExecutor: ExecutorService = newVpnTeardownExecutor(),
    private val teardownExecutorFactory: () -> ExecutorService = ::newVpnTeardownExecutor,
    private val rejectionExecutor: Executor = newVpnRejectionExecutor(),
) {
    private val lock = Any()
    private var teardownExecutor = initialTeardownExecutor
    private var leaseSequence = 0L
    private var generation = 0L
    private var teardownSequence = 0L
    private val activeLeases = mutableSetOf<Long>()
    private var activeConnection: ActiveConnection? = null
    private var activeTeardown: ActiveTeardown? = null

    val teardownInProgress: Boolean
        get() = synchronized(lock) { activeTeardown != null }

    fun acquire(): VpnServiceLifecycle = synchronized(lock) {
        val leaseId = ++leaseSequence
        activeLeases += leaseId
        VpnServiceLifecycle(this, leaseId)
    }

    internal fun start(leaseId: Long, serviceStartId: Int): VpnConnectionStart = synchronized(lock) {
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
        VpnConnectionStart.Accepted(ownGeneration)
    }

    internal fun attachSession(
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

    internal fun promoteRuntime(
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
            true
        }
    }

    internal fun beginConnectionWork(leaseId: Long, connectionGeneration: Long): Boolean = synchronized(lock) {
        activeConnection?.takeIf {
            it.leaseId == leaseId && it.generation == connectionGeneration
        }?.let { connection ->
            if (connection.connectionWorkStarted || connection.connectionWorkComplete) {
                return false
            }
            connection.connectionWorkStarted = true
            return true
        }
        activeTeardown?.takeIf {
            it.leaseId == leaseId && it.connectionGeneration == connectionGeneration
        }?.let { teardown ->
            if (teardown.connectionWorkStarted || teardown.connectionWorkComplete) {
                return false
            }
            teardown.connectionWorkStarted = true
            return true
        }
        false
    }

    internal fun discardConnectionWork(leaseId: Long, connectionGeneration: Long) = synchronized(lock) {
        activeConnection?.takeIf {
            it.leaseId == leaseId && it.generation == connectionGeneration
        }?.let { connection ->
            if (!connection.connectionWorkStarted) {
                connection.connectionWorkComplete = true
            }
            return@synchronized
        }
        activeTeardown?.takeIf {
            it.leaseId == leaseId && it.connectionGeneration == connectionGeneration
        }?.let { teardown ->
            if (!teardown.connectionWorkStarted) {
                teardown.connectionWorkComplete = true
                finishTeardownIfComplete(teardown)
            }
        }
    }

    internal fun finishConnectionWork(leaseId: Long, connectionGeneration: Long) = synchronized(lock) {
        val connection = activeConnection
        if (connection?.leaseId == leaseId && connection.generation == connectionGeneration) {
            if (connection.connectionWorkStarted) {
                connection.connectionWorkComplete = true
            }
            return@synchronized
        }
        activeTeardown?.takeIf {
            it.leaseId == leaseId && it.connectionGeneration == connectionGeneration
        }?.let { teardown ->
            if (teardown.connectionWorkStarted) {
                teardown.connectionWorkComplete = true
                finishTeardownIfComplete(teardown)
            }
        }
    }

    internal fun stop(
        leaseId: Long,
        expectedGeneration: Long?,
        serviceStartId: Int?,
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

        val teardown = detachConnection(connection, lifecycleComplete = false)
        VpnConnectionStop.Started(
            teardownId = teardown.id,
            serviceStartId = serviceStartId ?: connection.serviceStartId,
        )
    }

    internal fun finishLifecycleStop(leaseId: Long, teardownId: Long) = synchronized(lock) {
        activeTeardown?.takeIf { it.leaseId == leaseId && it.id == teardownId }?.let { teardown ->
            teardown.lifecycleComplete = true
            finishTeardownIfComplete(teardown)
        }
    }

    internal fun release(leaseId: Long) = synchronized(lock) {
        if (!activeLeases.remove(leaseId)) {
            return@synchronized
        }
        activeConnection?.takeIf { it.leaseId == leaseId }?.let { connection ->
            detachConnection(connection, lifecycleComplete = true)
        }
    }

    /** Test-only process teardown; production ownership intentionally survives service instances. */
    internal fun shutdownForTest() = synchronized(lock) {
        teardownExecutor.shutdown()
    }

    private fun detachConnection(
        connection: ActiveConnection,
        lifecycleComplete: Boolean,
    ): ActiveTeardown {
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
        )
        activeTeardown = teardown
        submitTeardown(teardown, resource)
        return teardown
    }

    private fun submitTeardown(teardown: ActiveTeardown, resource: AutoCloseable?) {
        val cleanup = Runnable { completeTeardown(teardown, resource) }
        try {
            teardownExecutor.execute(cleanup)
        } catch (submissionFailure: Throwable) {
            var reportedFailure = submissionFailure
            val replacement = try {
                teardownExecutorFactory()
            } catch (replacementFailure: Throwable) {
                reportedFailure = combineFailures(reportedFailure, replacementFailure)
                null
            }
            if (replacement != null) {
                teardownExecutor = replacement
                try {
                    replacement.execute(reportingCleanup(reportedFailure, cleanup))
                    return
                } catch (replacementFailure: Throwable) {
                    reportedFailure = combineFailures(reportedFailure, replacementFailure)
                }
            }
            try {
                rejectionExecutor.execute(reportingCleanup(reportedFailure, cleanup))
            } catch (rejectionFailure: Throwable) {
                reportedFailure = combineFailures(reportedFailure, rejectionFailure)
                Thread(
                    reportingCleanup(reportedFailure, cleanup),
                    "aurora-vpn-teardown-last-resort",
                ).start()
            }
        }
    }

    private fun reportingCleanup(submissionFailure: Throwable, cleanup: Runnable): Runnable = Runnable {
        reportFailure(submissionFailure)
        cleanup.run()
    }

    private fun completeTeardown(teardown: ActiveTeardown, resource: AutoCloseable?) {
        val failure = collectCleanupFailures({ resource?.close() })
        synchronized(lock) {
            if (activeTeardown === teardown) {
                teardown.resourceCleanupComplete = true
                finishTeardownIfComplete(teardown)
            }
        }
        failure?.let(::reportFailure)
    }

    private fun reportFailure(error: Throwable) {
        try {
            onTeardownFailure(error)
        } catch (_: Throwable) {
            // Diagnostics must never prevent the sole detached-resource owner from running.
        }
    }

    private fun finishTeardownIfComplete(teardown: ActiveTeardown) {
        if (activeTeardown === teardown &&
            teardown.lifecycleComplete &&
            teardown.resourceCleanupComplete &&
            teardown.connectionWorkComplete
        ) {
            activeTeardown = null
        }
    }

    private fun combineFailures(first: Throwable, next: Throwable): Throwable {
        if (first !== next) {
            first.addSuppressed(next)
        }
        return first
    }

    private class ActiveConnection(
        val leaseId: Long,
        val generation: Long,
        var serviceStartId: Int,
        var connectionWorkStarted: Boolean = false,
        var connectionWorkComplete: Boolean = false,
        var session: AutoCloseable? = null,
        var runtime: AutoCloseable? = null,
    )

    private class ActiveTeardown(
        val id: Long,
        val leaseId: Long,
        val connectionGeneration: Long,
        var connectionWorkStarted: Boolean,
        var connectionWorkComplete: Boolean,
        var lifecycleComplete: Boolean,
        var resourceCleanupComplete: Boolean = false,
    )
}

private fun newVpnTeardownExecutor(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "aurora-vpn-teardown")
}

private fun newVpnRejectionExecutor(): Executor = Executor { runnable ->
    Thread(runnable, "aurora-vpn-teardown-recovery").start()
}
