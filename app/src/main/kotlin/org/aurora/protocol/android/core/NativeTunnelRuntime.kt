package org.aurora.protocol.android.core

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    private val active = AtomicBoolean(false)
    private val writeLock = Any()

    fun start() {
        check(active.compareAndSet(false, true)) { "tunnel runtime is already active" }
        workers.execute(::runIngress)
        workers.execute(::runEgress)
    }

    override fun close() {
        if (active.compareAndSet(true, false)) {
            closeResources()
        }
    }

    private fun runIngress() {
        try {
            while (active.get()) {
                val packet = device.readPacket() ?: throw IllegalStateException("tunnel input closed")
                try {
                    val immediatePackets = session.ingressLocalPacket(packet)
                    immediatePackets.forEach(::writePacket)
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
            while (active.get()) {
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
                if (active.get()) {
                    device.writePacket(packet)
                }
            }
        } finally {
            packet.fill(0)
        }
    }

    private fun fail(error: Throwable) {
        if (active.compareAndSet(true, false)) {
            closeResources()
            onTerminalFailure(error)
        }
    }

    private fun closeResources() {
        try {
            session.close()
        } finally {
            try {
                device.close()
            } finally {
                workers.shutdownNow()
            }
        }
    }

    private companion object {
        const val maximumPacketBytes = 65535
    }
}
