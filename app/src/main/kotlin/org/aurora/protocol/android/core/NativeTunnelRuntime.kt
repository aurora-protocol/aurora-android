package org.aurora.protocol.android.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
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
    private val closeCompletion = CountDownLatch(1)
    private val closeFailure = AtomicReference<Throwable?>()
    private val runtimeWorkers = ConcurrentHashMap.newKeySet<Thread>()
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

    private fun transitionToClosed(): Boolean {
        val ownsClose = state.getAndSet(RuntimeState.CLOSED) != RuntimeState.CLOSED
        finishClose(ownsClose)
        return ownsClose
    }

    private fun finishClose(ownsClose: Boolean) {
        var interruption: InterruptedException? = null
        if (ownsClose) {
            try {
                closeResources()
            } catch (error: Throwable) {
                closeFailure.set(error)
            } finally {
                closeCompletion.countDown()
            }
        } else {
            interruption = awaitCloseCompletion()
        }
        if (Thread.currentThread() !in runtimeWorkers && workers.isShutdown) {
            interruption = awaitWorkerTermination(interruption)
        }
        finishInterruptedClose(interruption)
        closeFailure.get()?.let { throw it }
    }

    private fun awaitCloseCompletion(): InterruptedException? {
        var interruption: InterruptedException? = null
        while (true) {
            try {
                closeCompletion.await()
                break
            } catch (error: InterruptedException) {
                val first = interruption
                if (first == null) {
                    interruption = error
                } else if (first !== error) {
                    first.addSuppressed(error)
                }
            }
        }
        return interruption
    }

    private fun awaitWorkerTermination(initialInterruption: InterruptedException?): InterruptedException? {
        var interruption = initialInterruption
        while (!workers.isTerminated) {
            try {
                workers.awaitTermination(1, TimeUnit.DAYS)
            } catch (error: InterruptedException) {
                val first = interruption
                if (first == null) {
                    interruption = error
                } else if (first !== error) {
                    first.addSuppressed(error)
                }
            }
        }
        return interruption
    }

    private fun finishInterruptedClose(interruption: InterruptedException?) {
        interruption?.let { error ->
            Thread.currentThread().interrupt()
            closeFailure.get()?.let { failure ->
                if (failure !== error) {
                    failure.addSuppressed(error)
                }
                throw failure
            }
            throw error
        }
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
        val ownsClose = state.getAndSet(RuntimeState.CLOSED) != RuntimeState.CLOSED
        return try {
            finishClose(ownsClose)
            ownsClose
        } catch (closeFailure: Throwable) {
            if (closeFailure !== primaryFailure) {
                primaryFailure.addSuppressed(closeFailure)
            }
            ownsClose
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
