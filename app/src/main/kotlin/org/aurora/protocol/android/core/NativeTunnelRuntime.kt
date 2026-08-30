package org.aurora.protocol.android.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

internal interface TunnelPacketDevice : AutoCloseable {
    fun readPacket(): ByteArray?

    fun writePacket(packet: ByteArray)
}

internal class NativeTunnelRuntime(
    internal val session: NativePacketSession,
    internal val device: TunnelPacketDevice,
    internal val workers: ExecutorService = Executors.newFixedThreadPool(2),
    private val onTerminalFailure: (Throwable) -> Unit,
) : AutoCloseable {
    internal val state = AtomicReference(RuntimeState.READY)
    internal val closeCompletion = CountDownLatch(1)
    internal val closeFailure = AtomicReference<Throwable?>()
    internal val runtimeWorkers = ConcurrentHashMap.newKeySet<Thread>()
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
        val worker = Thread.currentThread()
        runtimeWorkers += worker
        try {
            while (isRunning()) {
                val packet = device.readPacket() ?: throw IllegalStateException("tunnel input closed")
                try {
                    require(packet.isNotEmpty() && packet.size <= maximumPacketBytes) {
                        "invalid tunnel input packet"
                    }
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
        } finally {
            runtimeWorkers -= worker
        }
    }

    private fun runEgress() {
        val worker = Thread.currentThread()
        runtimeWorkers += worker
        try {
            while (isRunning()) {
                writePacket(session.nextLocalPacket())
            }
        } catch (error: Throwable) {
            fail(error)
        } finally {
            runtimeWorkers -= worker
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

    internal companion object {
        const val maximumPacketBytes = 65535
    }

    internal enum class RuntimeState {
        READY,
        RUNNING,
        CLOSED,
    }
}
