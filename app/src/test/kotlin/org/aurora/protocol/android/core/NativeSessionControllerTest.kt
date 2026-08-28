package org.aurora.protocol.android.core

import java.io.IOException
import java.net.URL
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSessionControllerTest {
    @Test
    fun establishesOpaqueCoreSessionAndClearsProvisioningAndIssuerMaterial() {
        val provisioning = byteArrayOf(0x10, 0x20)
        val request = byteArrayOf(0x30, 0x40)
        val issuerResponse = byteArrayOf(0x50, 0x60)
        val expectedIssuerResponse = issuerResponse.copyOf()
        val core = FakeCore(beginPayload = issuerWorkPayload(request))
        val issuer = RecordingIssuerExchange(issuerResponse)
        val controller = NativeSessionController(core, issuer)

        assertEquals(7L, controller.establish(provisioning))

        assertArrayEquals(ByteArray(provisioning.size), provisioning)
        assertArrayEquals(request, issuer.requestBody)
        assertArrayEquals(ByteArray(issuer.requestBodyReference.size), issuer.requestBodyReference)
        assertArrayEquals(expectedIssuerResponse, core.issuerResponse)
        assertArrayEquals(ByteArray(issuerResponse.size), issuerResponse)
        assertTrue(controller.isEstablished)
        controller.close()
        assertEquals(listOf(7L), core.closedHandles)
        assertFalse(controller.isEstablished)
    }

    @Test
    fun closesThePendingCoreHandleWhenIssuerExchangeFails() {
        val core = FakeCore(beginPayload = issuerWorkPayload(byteArrayOf(0x30)))
        val issuer = FailingIssuerExchange()
        val controller = NativeSessionController(core, issuer)

        assertThrows(IllegalStateException::class.java) {
            controller.establish(byteArrayOf(0x10))
        }

        assertEquals(listOf(7L), core.closedHandles)
        assertArrayEquals(ByteArray(issuer.requestBodyReference.size), issuer.requestBodyReference)
        assertFalse(controller.isEstablished)
    }

    @Test
    fun preservesTheEstablishmentFailureWhenCoreHandleCleanupAlsoFails() {
        val cleanupFailure = IllegalStateException("core close failed")
        val core = FakeCore(
            beginPayload = issuerWorkPayload(byteArrayOf(0x30)),
            closeFailure = cleanupFailure,
        )
        val issuer = FailingIssuerExchange()
        val controller = NativeSessionController(core, issuer)

        val error = assertThrows(IllegalStateException::class.java) {
            controller.establish(byteArrayOf(0x10))
        }

        assertEquals("issuer unavailable", error.message)
        assertEquals(listOf(cleanupFailure), error.suppressed.toList())
        assertEquals(listOf(7L), core.closedHandles)
        assertFalse(controller.isEstablished)
    }

    @Test
    fun preservesTheEstablishmentFailureWhenCoreRejectsHandleCleanup() {
        val core = FakeCore(
            beginPayload = issuerWorkPayload(byteArrayOf(0x30)),
            closeResult = false,
        )
        val controller = NativeSessionController(core, FailingIssuerExchange())

        val error = assertThrows(IllegalStateException::class.java) {
            controller.establish(byteArrayOf(0x10))
        }

        assertEquals("issuer unavailable", error.message)
        assertEquals(listOf("Core native session close rejected"), error.suppressed.map { it.message })
        assertEquals(listOf(7L), core.closedHandles)
        assertFalse(controller.isEstablished)
    }

    @Test
    fun returnsImmediateAndDeferredLocalPacketsWithoutLeakingIngressInput() {
        val immediate = byteArrayOf(0x45, 0x00, 0x00, 0x14)
        val deferred = byteArrayOf(0x60, 0x00, 0x00, 0x00)
        val core = FakeCore(
            beginPayload = issuerWorkPayload(byteArrayOf(0x30)),
            ingressPayload = localPacketsPayload(immediate),
            nextPacket = deferred,
        )
        val controller = NativeSessionController(core, RecordingIssuerExchange(byteArrayOf(0x50)))
        controller.establish(byteArrayOf(0x10))
        val ingress = byteArrayOf(0x45, 0x00, 0x00, 0x14)

        val immediatePackets = controller.ingressLocalPacket(ingress)
        val nextPacket = controller.nextLocalPacket()

        try {
            assertArrayEquals(ByteArray(ingress.size), ingress)
            assertArrayEquals(byteArrayOf(0x45, 0x00, 0x00, 0x14), core.ingressPacket)
            assertArrayEquals(
                ByteArray(core.ingressResponsePayloadReference.size),
                core.ingressResponsePayloadReference,
            )
            assertEquals(1, immediatePackets.size)
            assertArrayEquals(immediate, immediatePackets.single())
            assertArrayEquals(deferred, nextPacket)
        } finally {
            immediatePackets.forEach { it.fill(0) }
            nextPacket.fill(0)
            controller.close()
        }
    }

    @Test
    fun clearsRejectedIngressPacketsWhenTheSessionIsUnavailable() {
        val controller = NativeSessionController(
            FakeCore(issuerWorkPayload(byteArrayOf(0x30))),
            RecordingIssuerExchange(byteArrayOf(0x50)),
        )
        val packet = byteArrayOf(0x45, 0x00, 0x00, 0x14)

        assertThrows(IllegalStateException::class.java) {
            controller.ingressLocalPacket(packet)
        }

        assertArrayEquals(ByteArray(packet.size), packet)
        controller.close()
    }

    @Test
    fun treatsStatusOnlyIngressConflictAsAPacketDropAndClearsTheCallerPacket() {
        val core = FakeCore(
            beginPayload = issuerWorkPayload(byteArrayOf(0x30)),
            ingressStatus = CoreStatus.CONFLICT,
            ingressPayload = ByteArray(0),
        )
        val controller = NativeSessionController(core, RecordingIssuerExchange(byteArrayOf(0x50)))
        controller.establish(byteArrayOf(0x10))
        val ingress = byteArrayOf(0x45, 0x00, 0x00, 0x14)

        val immediatePackets = controller.ingressLocalPacket(ingress)

        assertTrue(immediatePackets.isEmpty())
        assertArrayEquals(ByteArray(ingress.size), ingress)
        assertArrayEquals(byteArrayOf(0x45, 0x00, 0x00, 0x14), core.ingressPacket)
        assertArrayEquals(ByteArray(0), core.ingressResponsePayloadReference)
        assertTrue(controller.isEstablished)
        controller.close()
    }

    @Test
    fun keepsStatusOnlyIngressErrorTerminalAndClearsTheCallerPacket() {
        val core = FakeCore(
            beginPayload = issuerWorkPayload(byteArrayOf(0x30)),
            ingressStatus = CoreStatus.ERROR,
            ingressPayload = ByteArray(0),
        )
        val controller = NativeSessionController(core, RecordingIssuerExchange(byteArrayOf(0x50)))
        controller.establish(byteArrayOf(0x10))
        val ingress = byteArrayOf(0x45, 0x00, 0x00, 0x14)

        val error = assertThrows(IllegalStateException::class.java) {
            controller.ingressLocalPacket(ingress)
        }

        assertEquals("Core local packet ingress rejected", error.message)
        assertArrayEquals(ByteArray(ingress.size), ingress)
        assertArrayEquals(byteArrayOf(0x45, 0x00, 0x00, 0x14), core.ingressPacket)
        assertArrayEquals(ByteArray(0), core.ingressResponsePayloadReference)
        controller.close()
    }

    @Test
    fun clearsMalformedCoreIngressResultsAndCallerPackets() {
        val malformedResult = byteArrayOf(0x01, 0x00, 0x00, 0x02, 0x45)
        val core = FakeCore(
            beginPayload = issuerWorkPayload(byteArrayOf(0x30)),
            ingressPayload = malformedResult,
        )
        val controller = NativeSessionController(core, RecordingIssuerExchange(byteArrayOf(0x50)))
        controller.establish(byteArrayOf(0x10))
        val ingress = byteArrayOf(0x45, 0x00, 0x00, 0x14)

        assertThrows(IllegalArgumentException::class.java) {
            controller.ingressLocalPacket(ingress)
        }

        assertArrayEquals(ByteArray(ingress.size), ingress)
        assertArrayEquals(
            ByteArray(core.ingressResponsePayloadReference.size),
            core.ingressResponsePayloadReference,
        )
        controller.close()
    }

    @Test
    fun invokesTunnelSetupBeforeCoreOpensTheCarrier() {
        val core = FakeCore(beginPayload = issuerWorkPayload(byteArrayOf(0x30)))
        val controller = NativeSessionController(core, RecordingIssuerExchange(byteArrayOf(0x50)))
        var setupInvoked = false

        controller.establish(byteArrayOf(0x10)) {
            setupInvoked = true
            assertFalse(core.completed)
        }

        try {
            assertTrue(setupInvoked)
            assertTrue(core.completed)
        } finally {
            controller.close()
        }
    }

    @Test
    fun closesThePendingCoreHandleAndClearsMaterialWhenTunnelSetupFails() {
        val issuerResponse = byteArrayOf(0x50, 0x60)
        val core = FakeCore(beginPayload = issuerWorkPayload(byteArrayOf(0x30)))
        val issuer = RecordingIssuerExchange(issuerResponse)
        val controller = NativeSessionController(core, issuer)
        val provisioning = byteArrayOf(0x10, 0x20)

        val error = assertThrows(IllegalStateException::class.java) {
            controller.establish(provisioning) { throw IllegalStateException("TUN setup failed") }
        }

        assertEquals("TUN setup failed", error.message)
        assertArrayEquals(ByteArray(provisioning.size), provisioning)
        assertArrayEquals(ByteArray(issuerResponse.size), issuerResponse)
        assertArrayEquals(ByteArray(issuer.requestBodyReference.size), issuer.requestBodyReference)
        assertEquals(listOf(7L), core.closedHandles)
        assertFalse(controller.isEstablished)
    }

    @Test
    fun closesThePendingCoreHandleWhenCoreCompletionThrows() {
        val issuerResponse = byteArrayOf(0x50, 0x60)
        val core = FakeCore(
            beginPayload = issuerWorkPayload(byteArrayOf(0x30)),
            completeFailure = IOException("Core completion transport failed"),
        )
        val issuer = RecordingIssuerExchange(issuerResponse)
        val controller = NativeSessionController(core, issuer)

        val error = assertThrows(IOException::class.java) {
            controller.establish(byteArrayOf(0x10))
        }

        assertEquals("Core completion transport failed", error.message)
        assertArrayEquals(ByteArray(issuerResponse.size), issuerResponse)
        assertArrayEquals(ByteArray(issuer.requestBodyReference.size), issuer.requestBodyReference)
        assertEquals(listOf(7L), core.closedHandles)
        assertFalse(controller.isEstablished)
    }

    @Test
    fun closeFromTheTunnelSetupHookCancelsEstablishmentExactlyOnce() {
        val core = FakeCore(beginPayload = issuerWorkPayload(byteArrayOf(0x30)))
        val controller = NativeSessionController(core, RecordingIssuerExchange(byteArrayOf(0x50)))
        val outcome = arrayOfNulls<Throwable>(1)

        try {
            controller.establish(byteArrayOf(0x10)) {
                controller.close()
            }
        } catch (error: Throwable) {
            outcome[0] = error
        }

        assertTrue(outcome[0] is IllegalStateException)
        assertEquals("native session was cancelled", outcome[0]?.message)
        assertEquals(listOf(7L), core.closedHandles)
        assertFalse(controller.isEstablished)
        controller.close()
        assertEquals(listOf(7L), core.closedHandles)
    }

    @Test
    fun clearsProvisioningAndCreatesNoHandleWhenCoreBeginPayloadIsMalformed() {
        val core = FakeCore(beginPayload = byteArrayOf(0x7B, 0x7B))
        val controller = NativeSessionController(core, RecordingIssuerExchange(byteArrayOf(0x50)))
        val provisioning = byteArrayOf(0x10, 0x20)

        assertThrows(IllegalArgumentException::class.java) {
            controller.establish(provisioning)
        }

        assertArrayEquals(ByteArray(provisioning.size), provisioning)
        assertEquals(emptyList<Long>(), core.closedHandles)
        assertFalse(controller.isEstablished)
    }

    @Test
    fun clearsTheCallerPacketWhenTheCoreIngressCallThrows() {
        val core = FakeCore(
            beginPayload = issuerWorkPayload(byteArrayOf(0x30)),
            ingressFailure = IOException("Core ingress transport failed"),
        )
        val controller = NativeSessionController(core, RecordingIssuerExchange(byteArrayOf(0x50)))
        controller.establish(byteArrayOf(0x10))
        val ingress = byteArrayOf(0x45, 0x00, 0x00, 0x14)

        assertThrows(IOException::class.java) {
            controller.ingressLocalPacket(ingress)
        }

        assertArrayEquals(ByteArray(ingress.size), ingress)
        controller.close()
    }

    @Test
    fun refusesToStartAfterAnExplicitCloseAndClearsProvisioning() {
        val controller = NativeSessionController(FakeCore(issuerWorkPayload(byteArrayOf(0x30))), RecordingIssuerExchange(byteArrayOf(0x50)))
        val provisioning = byteArrayOf(0x10)
        controller.close()

        assertThrows(IllegalStateException::class.java) {
            controller.establish(provisioning)
        }

        assertArrayEquals(ByteArray(provisioning.size), provisioning)
    }

    @Test
    fun closeCancelsAnInFlightIssuerExchange() {
        val core = FakeCore(issuerWorkPayload(byteArrayOf(0x30)))
        val issuer = BlockingIssuerExchange()
        val controller = NativeSessionController(core, issuer)
        val result = arrayOfNulls<Throwable>(1)
        val worker = Thread {
            try {
                controller.establish(byteArrayOf(0x10))
            } catch (error: Throwable) {
                result[0] = error
            }
        }

        worker.start()
        assertTrue(issuer.started.await(2, TimeUnit.SECONDS))
        controller.close()
        worker.join(2_000)

        assertTrue(issuer.cancelled)
        assertTrue(result[0] is IllegalStateException)
        assertEquals(listOf(7L), core.closedHandles)
    }

    @Test
    fun closeDuringCoreBeginClosesTheReturnedHandle() {
        val beginStarted = CountDownLatch(1)
        val allowBeginToReturn = CountDownLatch(1)
        val core = FakeCore(
            beginPayload = issuerWorkPayload(byteArrayOf(0x30)),
            beginStarted = beginStarted,
            allowBeginToReturn = allowBeginToReturn,
        )
        val controller = NativeSessionController(core, RecordingIssuerExchange(byteArrayOf(0x50)))
        val result = arrayOfNulls<Throwable>(1)
        val worker = Thread {
            try {
                controller.establish(byteArrayOf(0x10))
            } catch (error: Throwable) {
                result[0] = error
            }
        }

        worker.start()
        assertTrue(beginStarted.await(2, TimeUnit.SECONDS))
        controller.close()
        allowBeginToReturn.countDown()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertTrue(result[0] is IllegalStateException)
        assertEquals(listOf(7L), core.closedHandles)
        assertFalse(controller.isEstablished)
    }

    @Test
    fun closesTheCoreHandleWhenIssuerCancellationFailsAndRemainsIdempotent() {
        val core = FakeCore(issuerWorkPayload(byteArrayOf(0x30)))
        val issuer = ThrowingCancellationIssuerExchange(byteArrayOf(0x50))
        val controller = NativeSessionController(core, issuer)
        controller.establish(byteArrayOf(0x10))

        val error = assertThrows(IllegalStateException::class.java) {
            controller.close()
        }

        assertEquals("issuer cancellation failed", error.message)
        assertEquals(listOf(7L), core.closedHandles)
        assertFalse(controller.isEstablished)
        controller.close()
        assertEquals(listOf(7L), core.closedHandles)
    }

    @Test
    fun reportsARejectedExplicitCoreCloseAndRemainsIdempotent() {
        val core = FakeCore(
            beginPayload = issuerWorkPayload(byteArrayOf(0x30)),
            closeResult = false,
        )
        val controller = NativeSessionController(core, RecordingIssuerExchange(byteArrayOf(0x50)))
        controller.establish(byteArrayOf(0x10))

        val error = assertThrows(IllegalStateException::class.java) {
            controller.close()
        }

        assertEquals("Core native session close rejected", error.message)
        assertEquals(listOf(7L), core.closedHandles)
        assertFalse(controller.isEstablished)
        controller.close()
        assertEquals(listOf(7L), core.closedHandles)
    }

    private class FakeCore(
        private val beginPayload: ByteArray,
        private val ingressStatus: CoreStatus = CoreStatus.OK,
        private val ingressPayload: ByteArray = localPacketsPayload(byteArrayOf(0x45)),
        private val nextPacket: ByteArray = byteArrayOf(0x60),
        private val beginStarted: CountDownLatch? = null,
        private val allowBeginToReturn: CountDownLatch? = null,
        private val ingressFailure: Throwable? = null,
        private val completeFailure: Throwable? = null,
        private val closeFailure: RuntimeException? = null,
        private val closeResult: Boolean = true,
    ) : NativeSessionCore {
        val closedHandles = mutableListOf<Long>()
        var completed = false
        lateinit var issuerResponse: ByteArray
        lateinit var ingressPacket: ByteArray
        lateinit var ingressResponsePayloadReference: ByteArray

        override fun beginNativeSession(provisioning: ByteArray): CoreResponse {
            beginStarted?.countDown()
            allowBeginToReturn?.await(2, TimeUnit.SECONDS)
            return CoreResponse(CoreStatus.OK, beginPayload.copyOf())
        }

        override fun completeNativeSession(handle: Long, issuerResponse: ByteArray): Boolean {
            completeFailure?.let { throw it }
            this.issuerResponse = issuerResponse.copyOf()
            completed = true
            return handle == 7L
        }

        override fun ingressLocalPacket(handle: Long, packet: ByteArray): CoreResponse {
            ingressFailure?.let { throw it }
            ingressPacket = packet.copyOf()
            val responsePayload = ingressPayload.copyOf()
            ingressResponsePayloadReference = responsePayload
            return CoreResponse(ingressStatus, responsePayload)
        }

        override fun nextLocalPacket(handle: Long): ByteArray = nextPacket.copyOf()

        override fun closeNativeSession(handle: Long): Boolean {
            closedHandles += handle
            closeFailure?.let { throw it }
            return closeResult
        }
    }

    private class RecordingIssuerExchange(private val response: ByteArray) : IssuerExchange {
        lateinit var requestBody: ByteArray
        lateinit var requestBodyReference: ByteArray

        override fun exchange(work: NativeIssuerWork): ByteArray {
            requestBody = work.requestBody.copyOf()
            requestBodyReference = work.requestBody
            return response
        }
    }

    private class FailingIssuerExchange : IssuerExchange {
        lateinit var requestBodyReference: ByteArray

        override fun exchange(work: NativeIssuerWork): ByteArray {
            requestBodyReference = work.requestBody
            throw IllegalStateException("issuer unavailable")
        }
    }

    private class BlockingIssuerExchange : CancellableIssuerExchange {
        val started = CountDownLatch(1)
        @Volatile var cancelled = false

        override fun exchange(work: NativeIssuerWork): ByteArray {
            started.countDown()
            while (!cancelled) {
                Thread.sleep(10)
            }
            throw IllegalStateException("issuer cancelled")
        }

        override fun cancel() {
            cancelled = true
        }
    }

    private class ThrowingCancellationIssuerExchange(
        private val response: ByteArray,
    ) : CancellableIssuerExchange {
        override fun exchange(work: NativeIssuerWork): ByteArray = response

        override fun cancel() {
            throw IllegalStateException("issuer cancellation failed")
        }
    }

    private companion object {
        fun issuerWorkPayload(request: ByteArray): ByteArray = """
            {
              "handle":7,
              "issuer_url":"https://issuer.example",
              "issuer_carrier_path":"/assets/issue",
              "request_body_base64":"${Base64.getEncoder().encodeToString(request)}"
            }
        """.trimIndent().toByteArray()

        fun localPacketsPayload(packet: ByteArray): ByteArray {
            require(packet.isNotEmpty() && packet.size <= 65_535)
            return ByteArray(1 + 3 + packet.size).also { encoded ->
                encoded[0] = 0x01
                encoded[1] = (packet.size ushr 16).toByte()
                encoded[2] = (packet.size ushr 8).toByte()
                encoded[3] = packet.size.toByte()
                System.arraycopy(packet, 0, encoded, 4, packet.size)
            }
        }
    }
}
