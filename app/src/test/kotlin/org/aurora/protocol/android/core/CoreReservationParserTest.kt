package org.aurora.protocol.android.core

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoreReservationParserTest {
    @Test
    fun decodesTheExactOpaqueReservationSchema() {
        val provisioning = byteArrayOf(0x01, 0x02, 0x03)
        val spentHintKey = ByteArray(48) { it.toByte() }
        val relayBucketId = ByteArray(16) { (it + 48).toByte() }
        val encoded = """
            {
              "provisioning_base64":"${Base64.getEncoder().encodeToString(provisioning)}",
              "spent_hint_key_base64":"${Base64.getEncoder().encodeToString(spentHintKey)}",
              "relay_bucket_id_base64":"${Base64.getEncoder().encodeToString(relayBucketId)}",
              "access_hint_expiry_unix":123456789
            }
        """.trimIndent().toByteArray()

        val reservation = CoreReservationParser.decode(encoded)

        assertArrayEquals(provisioning, reservation.provisioning)
        assertArrayEquals(spentHintKey, reservation.spentHintKey)
        assertArrayEquals(relayBucketId, reservation.relayBucketId)
        assertEquals(123456789L, reservation.accessHintExpiryUnix)
    }

    @Test
    fun rejectsUnknownOrMalformedReservationFields() {
        val unknown = """
            {
              "provisioning_base64":"AQ==",
              "spent_hint_key_base64":"",
              "relay_bucket_id_base64":"",
              "access_hint_expiry_unix":1,
              "unexpected":true
            }
        """.trimIndent().toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            CoreReservationParser.decode(unknown)
        }
    }

    @Test
    fun rejectsCoercedReservationExpiryValues() {
        val spentHintKey = Base64.getEncoder().encodeToString(ByteArray(48))
        val relayBucketId = Base64.getEncoder().encodeToString(ByteArray(16))
        val encoded = """
            {
              "provisioning_base64":"AQ==",
              "spent_hint_key_base64":"$spentHintKey",
              "relay_bucket_id_base64":"$relayBucketId",
              "access_hint_expiry_unix":"1"
            }
        """.trimIndent().toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            CoreReservationParser.decode(encoded)
        }
    }
}
