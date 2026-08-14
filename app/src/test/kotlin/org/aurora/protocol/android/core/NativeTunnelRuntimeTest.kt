package org.aurora.protocol.android.core

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
}
