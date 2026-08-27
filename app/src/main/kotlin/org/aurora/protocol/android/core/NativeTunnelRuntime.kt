package org.aurora.protocol.android.core

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

internal interface TunnelPacketDevice : AutoCloseable {
    fun readPacket(): ByteArray?
    fun writePacket(packet: ByteArray)
}

internal class NativeTunnelRuntime(
    private val session: NativePacketSession,
    private val device: TunnelPacketDevice,
    private val workers: ExecutorService = Executors.newFixedThreadPool(2),
    private val onTerminalFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val state = AtomicReference(RuntimeState.READY)
    private val writeLock = Any()

    fun start() {
        if (!state.compareAndSet(RuntimeState.READY, RuntimeState.RUNNING)) {
            if (state.get() == RuntimeState.CLOSED) {
                return
            }
            throw IllegalStateException("tunnel runtime is already active")
        }
        try {
            workers.execute(::runIngress)
            workers.execute(::runEgress)
        } catch (error: RejectedExecutionException) {
            if (transitionToClosedPreserving(error)) {
                throw error
            }
        }
    }

    override fun close() {
        transitionToClosed()
    }

    private fun runIngress() {
        try {
            while (isRunning()) {
                val packet = device.readPacket() ?: throw IllegalStateException("tunnel input closed")
                try {
                    val immediatePackets = session.ingressLocalPacket(packet)
                    try {
                        immediatePackets.forEach(::writePacket)
                    } finally {
                        immediatePackets.forEach { it.fill(0) }
                    }
                } finally {
                    packet.fill(0)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun runEgress() {
        try {
            while (isRunning()) {
                writePacket(session.nextLocalPacket())
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun writePacket(packet: ByteArray) {
        try {
            require(packet.isNotEmpty() && packet.size <= maximumPacketBytes) { "invalid Core local packet" }
            synchronized(writeLock) {
                if (isRunning()) {
                    device.writePacket(packet)
                }
            }
        } finally {
            packet.fill(0)
        }
    }

    private fun fail(error: Throwable) {
        if (transitionToClosedPreserving(error)) {
            onTerminalFailure(error)
        }
    }

    private fun isRunning(): Boolean = state.get() == RuntimeState.RUNNING

    private fun transitionToClosed(): Boolean {
        if (state.getAndSet(RuntimeState.CLOSED) == RuntimeState.CLOSED) {
            return false
        }
        closeResources()
        return true
    }

    private fun closeResources() {
        var failure: Throwable? = null
        try {
            session.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            device.close()
        } catch (error: Throwable) {
            failure = combineFailures(failure, error)
        }
        try {
            workers.shutdownNow()
        } catch (error: Throwable) {
            failure = combineFailures(failure, error)
        }
        failure?.let { throw it }
    }

    private fun transitionToClosedPreserving(primaryFailure: Throwable): Boolean {
        return try {
            transitionToClosed()
        } catch (closeFailure: Throwable) {
            if (closeFailure !== primaryFailure) {
                primaryFailure.addSuppressed(closeFailure)
            }
            true
        }
    }

    private fun combineFailures(first: Throwable?, next: Throwable): Throwable {
        if (first == null) {
            return next
        }
        if (first !== next) {
            first.addSuppressed(next)
        }
        return first
    }

    private companion object {
        const val maximumPacketBytes = 65535
    }

    private enum class RuntimeState {
        READY,
        RUNNING,
        CLOSED,
    }
}
