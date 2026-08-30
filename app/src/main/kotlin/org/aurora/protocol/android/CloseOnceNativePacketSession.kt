package org.aurora.protocol.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.aurora.protocol.android.core.NativePacketSession

internal class CloseOnceNativePacketSession(
    private val delegate: NativePacketSession,
) : NativePacketSession {
    private val closeStarted = AtomicBoolean()
    private val closeCompletion = CountDownLatch(1)
    private val closeFailure = AtomicReference<Throwable?>()

    override fun ingressLocalPacket(packet: ByteArray): List<ByteArray> = delegate.ingressLocalPacket(packet)

    override fun nextLocalPacket(): ByteArray = delegate.nextLocalPacket()

    override fun close() {
        if (closeStarted.compareAndSet(false, true)) {
            try {
                delegate.close()
            } catch (error: Throwable) {
                closeFailure.set(error)
            } finally {
                closeCompletion.countDown()
            }
        } else {
            awaitCloseCompletion()
        }
        closeFailure.get()?.let { throw it }
    }

    private fun awaitCloseCompletion() {
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
}

internal class UnavailableProvisioningException : IllegalStateException("no usable stored provisioning reservation")
