package org.aurora.protocol.android.core

import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the real libauroracore through the JNI bridge on device. The trust
 * fixture is already configured by AuroraApplication startup in the
 * instrumented process; Core treats reconfiguring identical canonical trust as
 * a no-op, so the acceptance assertion holds either way.
 */
class NativeCoreJniInstrumentedTest {
    @Test
    fun acceptsTheCheckedInTrustFixtureAndRejectsGarbage() {
        val fixture = InstrumentationRegistry.getInstrumentation().targetContext.assets
            .open("AuroraSignedSeedTrust.bin").use { it.readBytes() }
        assertTrue(fixture.isNotEmpty())

        assertFalse(NativeCoreJni.configureNativeProvisioningTrust(byteArrayOf(0x01, 0x02, 0x03)))
        assertTrue(NativeCoreJni.configureNativeProvisioningTrust(fixture))
    }

    @Test
    fun rejectsEmptyInputsBeforeCrossingTheJniBoundary() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeCoreJni.beginNativeSession(ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeCoreJni.reserveNativeProvisioning(ByteArray(0), 1_700_000_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeCoreJni.reserveNativeProvisioning(reservationRequest(), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeCoreJni.closeNativeSession(0)
        }
    }

    @Test
    fun reportsCoreErrorForGarbageProvisioningAndReservation() {
        NativeCoreJni.beginNativeSession(byteArrayOf(0x00, 0x01, 0x02)).use { response ->
            assertEquals(CoreStatus.ERROR, response.status)
            assertTrue(response.payload.isEmpty())
        }
        NativeCoreJni.reserveNativeProvisioning(reservationRequest(), 1_700_000_000).use { response ->
            assertEquals(CoreStatus.ERROR, response.status)
            assertTrue(response.payload.isEmpty())
        }
    }

    @Test
    fun rejectsUnknownSessionHandles() {
        assertFalse(NativeCoreJni.closeNativeSession(1))
        assertFalse(NativeCoreJni.completeNativeSession(1, byteArrayOf(0x01)))
        NativeCoreJni.ingressLocalPacket(1, byteArrayOf(0x01, 0x02)).use { response ->
            assertEquals(CoreStatus.ERROR, response.status)
            assertTrue(response.payload.isEmpty())
        }
        assertThrows(IllegalStateException::class.java) {
            NativeCoreJni.nextLocalPacket(1)
        }
    }

    private fun reservationRequest(source: ByteArray = byteArrayOf(0x10, 0x20, 0x30)): ByteArray =
        ByteBuffer.allocate(Int.SIZE_BYTES + source.size + 1)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(source.size)
            .put(source)
            .put(0)
            .array()
}
