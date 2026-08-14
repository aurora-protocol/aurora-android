package org.aurora.protocol.android.core

import java.net.URL
import java.util.Base64
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
    fun refusesToStartAfterAnExplicitCloseAndClearsProvisioning() {
        val controller = NativeSessionController(FakeCore(issuerWorkPayload(byteArrayOf(0x30))), RecordingIssuerExchange(byteArrayOf(0x50)))
        val provisioning = byteArrayOf(0x10)
        controller.close()

        assertThrows(IllegalStateException::class.java) {
            controller.establish(provisioning)
        }

        assertArrayEquals(ByteArray(provisioning.size), provisioning)
    }

    private class FakeCore(
        private val beginPayload: ByteArray,
        private val ingressPayload: ByteArray = localPacketsPayload(byteArrayOf(0x45)),
        private val nextPacket: ByteArray = byteArrayOf(0x60),
    ) : NativeSessionCore {
        val closedHandles = mutableListOf<Long>()
        var completed = false
        lateinit var issuerResponse: ByteArray
        lateinit var ingressPacket: ByteArray

        override fun beginNativeSession(provisioning: ByteArray): CoreResponse =
            CoreResponse(CoreStatus.OK, beginPayload.copyOf())

        override fun completeNativeSession(handle: Long, issuerResponse: ByteArray): Boolean {
            this.issuerResponse = issuerResponse.copyOf()
            completed = true
            return handle == 7L
        }

        override fun ingressLocalPacket(handle: Long, packet: ByteArray): CoreResponse {
            ingressPacket = packet.copyOf()
            return CoreResponse(CoreStatus.OK, ingressPayload.copyOf())
        }

        override fun nextLocalPacket(handle: Long): ByteArray = nextPacket.copyOf()

        override fun closeNativeSession(handle: Long): Boolean {
            closedHandles += handle
            return true
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

    private companion object {
        fun issuerWorkPayload(request: ByteArray): ByteArray = """
            {
              "handle":7,
              "issuer_url":"https://issuer.example",
              "issuer_carrier_path":"/assets/issue",
              "request_body_base64":"${Base64.getEncoder().encodeToString(request)}"
            }
        """.trimIndent().toByteArray()

        fun localPacketsPayload(packet: ByteArray): ByteArray = """
            {"packets_base64":["${Base64.getEncoder().encodeToString(packet)}"]}
        """.trimIndent().toByteArray()
    }
}
