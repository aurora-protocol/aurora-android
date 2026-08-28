package org.aurora.protocol.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeProvisioningReservationClientTest {
    @Test
    fun decodesSuccessfulReservationsBeforeClearingTheCoreResponse() {
        val payload = encodedCoreReservation()
        val response = CoreResponse(CoreStatus.OK, payload)
        val client = NativeProvisioningReservationClient { _, _ -> response }

        val reservation = client.reserve(byteArrayOf(0x01), 123)
        try {
            assertArrayEquals(byteArrayOf(0x01, 0x02), reservation.provisioning)
            assertEquals(123L, reservation.accessHintExpiryUnix)
            assertArrayEquals(ByteArray(payload.size), payload)
        } finally {
            reservation.close()
        }
    }

    @Test
    fun rejectsFailedCoreResponsesAndStillClearsTheirPayload() {
        val payload = byteArrayOf(0x55)
        val response = CoreResponse(CoreStatus.ERROR, payload)
        val client = NativeProvisioningReservationClient { _, _ -> response }

        assertThrows(IllegalStateException::class.java) {
            client.reserve(byteArrayOf(0x01), 123)
        }

        assertArrayEquals(ByteArray(payload.size), payload)
    }
}
