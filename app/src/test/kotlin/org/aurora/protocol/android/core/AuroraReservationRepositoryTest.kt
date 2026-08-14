package org.aurora.protocol.android.core

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AuroraReservationRepositoryTest {
    @Test
    fun reservesAndTransfersTheResultToEncryptedStorage() {
        val responsePayload = encodedReservation()
        val response = CoreResponse(CoreStatus.OK, responsePayload)
        val core = NativeProvisioningReservationClient { _, _ -> response }
        val storage = RecordingStorage()
        val repository = AuroraReservationRepository(core, storage)

        repository.reserveAndPersist(byteArrayOf(0x01), 123)

        assertArrayEquals(byteArrayOf(0x01, 0x02), storage.provisioning)
        assertArrayEquals(ByteArray(responsePayload.size), responsePayload)
    }

    private fun encodedReservation(): ByteArray {
        val spentHintKey = Base64.getEncoder().encodeToString(ByteArray(48))
        val relayBucketId = Base64.getEncoder().encodeToString(ByteArray(16))
        return """
            {
              "provisioning_base64":"AQI=",
              "spent_hint_key_base64":"$spentHintKey",
              "relay_bucket_id_base64":"$relayBucketId",
              "access_hint_expiry_unix":123
            }
        """.trimIndent().toByteArray()
    }

    private class RecordingStorage : ReservationStore {
        var provisioning = ByteArray(0)

        override fun save(reservation: CoreReservation) {
            provisioning = reservation.provisioning.copyOf()
            reservation.close()
        }

        override fun load(): CoreReservation? = null

        override fun clear() = Unit
    }
}
