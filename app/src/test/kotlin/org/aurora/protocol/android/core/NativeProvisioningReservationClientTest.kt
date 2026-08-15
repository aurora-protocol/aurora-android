package org.aurora.protocol.android.core

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeProvisioningReservationClientTest {
    @Test
    fun decodesSuccessfulReservationsBeforeClearingTheCoreResponse() {
        val payload = encodedReservation()
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

    private fun encodedReservation(): ByteArray {
        val spentHintKey = Base64.getEncoder().encodeToString(ByteArray(48) { it.toByte() })
        val relayBucketId = Base64.getEncoder().encodeToString(ByteArray(16) { (it + 48).toByte() })
        return """
            {
              "provisioning_base64":"AQI=",
              "spent_hint_key_base64":"$spentHintKey",
              "relay_bucket_id_base64":"$relayBucketId",
              "access_hint_expiry_unix":123
            }
        """.trimIndent().toByteArray()
    }
}
