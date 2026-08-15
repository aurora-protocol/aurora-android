package org.aurora.protocol.android.core

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
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

    private class FakeTunnelPacketDevice(private val firstPacket: ByteArray) : TunnelPacketDevice {
        private val inbound = LinkedBlockingQueue<ByteArray>()
        private val ingressCleanupReached = CountDownLatch(1)
        val written = LinkedBlockingQueue<ByteArray>()
        var closed = false

        init {
            inbound.offer(firstPacket)
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
}
