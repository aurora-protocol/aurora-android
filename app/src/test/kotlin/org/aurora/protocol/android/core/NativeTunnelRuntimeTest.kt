package org.aurora.protocol.android.core

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTunnelRuntimeTest {
    @Test
    fun closesResourcesAndDoesNotStartWhenStoppedBeforeWorkersAreSubmitted() {
        val device = FakeTunnelPacketDevice(byteArrayOf(0x45))
        val session = FakeNativePacketSession()
        val workers = Executors.newFixedThreadPool(2)
        val runtime = NativeTunnelRuntime(session, device, workers) { throw AssertionError("unexpected terminal failure", it) }

        try {
            runtime.close()
            runtime.start()
            assertTrue(session.closed)
            assertTrue(device.closed)
            assertTrue(workers.isShutdown)
        } finally {
            runtime.close()
            workers.shutdownNow()
        }
    }

    @Test
    fun forwardsImmediateAndDeferredPacketsThenClosesBothResources() {
        val ingress = byteArrayOf(0x45, 0x00, 0x00, 0x14)
        val device = FakeTunnelPacketDevice(ingress)
        val session = FakeNativePacketSession()
        val runtime = NativeTunnelRuntime(session, device) { throw AssertionError("unexpected terminal failure", it) }

        runtime.start()

        try {
            assertArrayEquals(byteArrayOf(0x45, 0x00), device.written.poll(2, TimeUnit.SECONDS))
            session.deferredPackets.offer(byteArrayOf(0x60, 0x00, 0x00, 0x00))
            assertArrayEquals(byteArrayOf(0x60, 0x00, 0x00, 0x00), device.written.poll(2, TimeUnit.SECONDS))
            assertTrue(device.awaitIngressCleanup(2, TimeUnit.SECONDS))
            assertArrayEquals(ByteArray(ingress.size), ingress)
        } finally {
            runtime.close()
        }
        assertTrue(session.closed)
        assertTrue(device.closed)
    }

    @Test
    fun continuesIngressAfterANonterminalPacketDrop() {
        val dropped = byteArrayOf(0x45, 0x00, 0x00, 0x14)
        val accepted = byteArrayOf(0x60, 0x00, 0x00, 0x00)
        val immediate = byteArrayOf(0x60, 0x01)
        val device = FakeTunnelPacketDevice(dropped, accepted)
        val session = DropThenForwardNativePacketSession(immediate)
        val terminalFailures = LinkedBlockingQueue<Throwable>()
        val runtime = NativeTunnelRuntime(session, device) { terminalFailures.offer(it) }

        runtime.start()

        try {
            assertArrayEquals(immediate, device.written.poll(2, TimeUnit.SECONDS))
            assertEquals(2, session.ingressCalls.get())
            assertArrayEquals(ByteArray(dropped.size), dropped)
            assertArrayEquals(ByteArray(accepted.size), accepted)
            assertEquals(null, terminalFailures.poll())
        } finally {
            runtime.close()
        }
        assertTrue(session.closed)
        assertTrue(device.closed)
    }

    @Test
    fun clearsEveryImmediatePacketWhenTunnelWriteFails() {
        val ingress = byteArrayOf(0x45, 0x00, 0x00, 0x14)
        val immediateFirst = byteArrayOf(0x45, 0x00)
        val immediateSecond = byteArrayOf(0x60, 0x00)
        val failure = CountDownLatch(1)
        val device = FailingWriteTunnelPacketDevice(ingress)
        val session = FailingImmediatePacketSession(immediateFirst, immediateSecond)
        val workers = Executors.newFixedThreadPool(2)
        val runtime = NativeTunnelRuntime(session, device, workers) { failure.countDown() }

        try {
            runtime.start()
            assertTrue(failure.await(2, TimeUnit.SECONDS))
            assertArrayEquals(ByteArray(immediateFirst.size), immediateFirst)
            assertArrayEquals(ByteArray(immediateSecond.size), immediateSecond)
        } finally {
            runtime.close()
            workers.shutdownNow()
        }
    }

    @Test
    fun reportsTheWorkerFailureWhenResourceCleanupAlsoFails() {
        val workerFailure = IOException("tunnel read failed")
        val cleanupFailure = IOException("tunnel device close failed")
        val terminalFailure = LinkedBlockingQueue<Throwable>()
        val workers = Executors.newFixedThreadPool(2)
        val session = FakeNativePacketSession()
        val device = FailingReadAndCloseTunnelPacketDevice(workerFailure, cleanupFailure)
        val runtime = NativeTunnelRuntime(session, device, workers) { terminalFailure.offer(it) }

        runtime.start()

        val reported = terminalFailure.poll(2, TimeUnit.SECONDS)
        assertSame(workerFailure, reported)
        assertEquals(listOf(cleanupFailure), reported?.suppressed?.toList())
        assertTrue(session.closed)
        assertTrue(device.closeAttempted)
        assertTrue(workers.isShutdown)
        assertSame(cleanupFailure, assertThrows(IOException::class.java) { runtime.close() })
    }

    @Test
    fun preservesExecutorRejectionWhenResourceCleanupAlsoFails() {
        val cleanupFailure = IOException("tunnel device close failed")
        val workers = Executors.newSingleThreadExecutor().apply { shutdown() }
        val session = FakeNativePacketSession()
        val device = FailingReadAndCloseTunnelPacketDevice(
            readFailure = AssertionError("worker must not run"),
            closeFailure = cleanupFailure,
        )
        val runtime = NativeTunnelRuntime(session, device, workers) { throw AssertionError("unexpected terminal failure", it) }

        val error = assertThrows(RejectedExecutionException::class.java) {
            runtime.start()
        }

        assertEquals(listOf(cleanupFailure), error.suppressed.toList())
        assertTrue(session.closed)
        assertTrue(device.closeAttempted)
        assertTrue(workers.isShutdown)
        assertSame(cleanupFailure, assertThrows(IOException::class.java) { runtime.close() })
    }

    @Test
    fun concurrentStopWaitsForFatalWorkerResourceCloseToFinish() {
        val workerFailure = IOException("tunnel read failed")
        val session = BlockingCloseNativePacketSession()
        val device = FatalReadTunnelPacketDevice(workerFailure)
        val workers = Executors.newFixedThreadPool(2)
        val terminalFailure = LinkedBlockingQueue<Throwable>()
        val runtime = NativeTunnelRuntime(session, device, workers) { terminalFailure.offer(it) }
        val stopEntered = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val stopFailure = AtomicReference<Throwable?>()

        runtime.start()
        assertTrue(session.closeStarted.await(2, TimeUnit.SECONDS))
        val stop = thread(start = true, name = "tunnel-close-join-test") {
            stopEntered.countDown()
            try {
                runtime.close()
            } catch (error: Throwable) {
                stopFailure.set(error)
            } finally {
                stopReturned.countDown()
            }
        }

        try {
            assertTrue(stopEntered.await(2, TimeUnit.SECONDS))
            assertFalse(stopReturned.await(200, TimeUnit.MILLISECONDS))
            assertEquals(1, session.closeCalls.get())
            assertEquals(0, device.closeCalls.get())

            session.allowClose.countDown()
            assertTrue(stopReturned.await(2, TimeUnit.SECONDS))
            stop.join(2_000)
            assertFalse(stop.isAlive)
            assertEquals(null, stopFailure.get())
            assertSame(workerFailure, terminalFailure.poll(2, TimeUnit.SECONDS))
            assertEquals(1, session.closeCalls.get())
            assertEquals(1, device.closeCalls.get())
            assertTrue(workers.isShutdown)
            assertTrue(workers.isTerminated)
        } finally {
            session.allowClose.countDown()
            stop.join(2_000)
            workers.shutdownNow()
        }
    }

    private class FakeNativePacketSession : NativePacketSession {
        val deferredPackets = LinkedBlockingQueue<ByteArray>()
        var closed = false

        override fun ingressLocalPacket(packet: ByteArray): List<ByteArray> = listOf(byteArrayOf(0x45, 0x00))

        override fun nextLocalPacket(): ByteArray = deferredPackets.take()

        override fun close() {
            closed = true
            deferredPackets.offer(byteArrayOf(0x45))
        }
    }

    private class DropThenForwardNativePacketSession(
        private val immediate: ByteArray,
    ) : NativePacketSession {
        private val deferredPackets = LinkedBlockingQueue<ByteArray>()
        val ingressCalls = AtomicInteger()
        var closed = false

        override fun ingressLocalPacket(packet: ByteArray): List<ByteArray> =
            if (ingressCalls.incrementAndGet() == 1) emptyList() else listOf(immediate.copyOf())

        override fun nextLocalPacket(): ByteArray = deferredPackets.take()

        override fun close() {
            closed = true
            deferredPackets.offer(byteArrayOf(0x45))
        }
    }

    private class FakeTunnelPacketDevice(vararg packets: ByteArray) : TunnelPacketDevice {
        private val inbound = LinkedBlockingQueue<ByteArray>()
        private val ingressCleanupReached = CountDownLatch(1)
        val written = LinkedBlockingQueue<ByteArray>()
        var closed = false

        init {
            packets.forEach(inbound::offer)
        }

        override fun readPacket(): ByteArray? {
            if (inbound.isEmpty()) {
                ingressCleanupReached.countDown()
            }
            return inbound.take()
        }

        fun awaitIngressCleanup(timeout: Long, unit: TimeUnit): Boolean = ingressCleanupReached.await(timeout, unit)

        override fun writePacket(packet: ByteArray) {
            written.offer(packet.copyOf())
        }

        override fun close() {
            closed = true
        }
    }

    private class FailingImmediatePacketSession(
        private val first: ByteArray,
        private val second: ByteArray,
    ) : NativePacketSession {
        private val deferredPackets = LinkedBlockingQueue<ByteArray>()

        override fun ingressLocalPacket(packet: ByteArray): List<ByteArray> = listOf(first, second)

        override fun nextLocalPacket(): ByteArray = deferredPackets.take()

        override fun close() {
            deferredPackets.offer(byteArrayOf(0x45))
        }
    }

    private class FailingWriteTunnelPacketDevice(firstPacket: ByteArray) : TunnelPacketDevice {
        private val inbound = LinkedBlockingQueue<ByteArray>()

        init {
            inbound.offer(firstPacket)
        }

        override fun readPacket(): ByteArray? = inbound.take()

        override fun writePacket(packet: ByteArray) {
            throw IOException("test tunnel write failure")
        }

        override fun close() {
            inbound.offer(byteArrayOf(0x45))
        }
    }

    private class FailingReadAndCloseTunnelPacketDevice(
        private val readFailure: Throwable,
        private val closeFailure: Throwable,
    ) : TunnelPacketDevice {
        var closeAttempted = false

        override fun readPacket(): ByteArray? = throw readFailure

        override fun writePacket(packet: ByteArray) = Unit

        override fun close() {
            closeAttempted = true
            throw closeFailure
        }
    }

    private class BlockingCloseNativePacketSession : NativePacketSession {
        private val deferredPackets = LinkedBlockingQueue<ByteArray>()
        val closeCalls = AtomicInteger()
        val closeStarted = CountDownLatch(1)
        val allowClose = CountDownLatch(1)

        override fun ingressLocalPacket(packet: ByteArray): List<ByteArray> = emptyList()

        override fun nextLocalPacket(): ByteArray = deferredPackets.take()

        override fun close() {
            closeCalls.incrementAndGet()
            closeStarted.countDown()
            try {
                check(allowClose.await(10, TimeUnit.SECONDS))
            } finally {
                deferredPackets.offer(byteArrayOf(0x45))
            }
        }
    }

    private class FatalReadTunnelPacketDevice(
        private val readFailure: Throwable,
    ) : TunnelPacketDevice {
        val closeCalls = AtomicInteger()

        override fun readPacket(): ByteArray? = throw readFailure

        override fun writePacket(packet: ByteArray) = Unit

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }
}
