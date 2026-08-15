package org.aurora.protocol.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EncryptedReservationStoreTest {
    @Test
    fun encryptsReservationsAndTransfersOwnershipToTheStore() {
        val blobs = MemoryBlobStore()
        val store = EncryptedReservationStore(blobs, InvertingCipher)
        val reservation = reservation()

        store.save(reservation)

        assertArrayEquals(ByteArray(3), reservation.provisioning)
        assertArrayEquals(ByteArray(48), reservation.spentHintKey)
        assertArrayEquals(ByteArray(16), reservation.relayBucketId)
        assertNotNull(blobs.value)
        assertFalse(
            blobs.value!!.copyOfRange(0, 8).contentEquals(byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x03, 0x01, 0x02, 0x03)),
        )

        val restored = store.load()
        requireNotNull(restored)
        try {
            assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), restored.provisioning)
            assertArrayEquals(ByteArray(48) { it.toByte() }, restored.spentHintKey)
            assertArrayEquals(ByteArray(16) { (it + 48).toByte() }, restored.relayBucketId)
        } finally {
            restored.close()
        }
    }

    @Test
    fun clearsCorruptedPersistedReservations() {
        val blobs = MemoryBlobStore(byteArrayOf(0x7f))
        val store = EncryptedReservationStore(blobs, object : ReservationCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.copyOf()

            override fun decrypt(ciphertext: ByteArray): ByteArray = byteArrayOf(0x00)
        })

        assertThrows(ReservationStorageException::class.java) {
            store.load()
        }

        assertNull(blobs.value)
    }

    private fun reservation(): CoreReservation {
        return CoreReservation(
            provisioning = byteArrayOf(0x01, 0x02, 0x03),
            spentHintKey = ByteArray(48) { it.toByte() },
            relayBucketId = ByteArray(16) { (it + 48).toByte() },
            accessHintExpiryUnix = 123,
        )
    }

    private object InvertingCipher : ReservationCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = invert(plaintext)

        override fun decrypt(ciphertext: ByteArray): ByteArray = invert(ciphertext)

        private fun invert(value: ByteArray): ByteArray = ByteArray(value.size) { index ->
            (value[index].toInt() xor 0xa5).toByte()
        }
    }

    private class MemoryBlobStore(initial: ByteArray? = null) : EncryptedReservationBlobStore {
        var value: ByteArray? = initial?.copyOf()

        override fun write(encrypted: ByteArray) {
            value = encrypted.copyOf()
        }

        override fun read(): ByteArray? = value?.copyOf()

        override fun clear() {
            value?.fill(0)
            value = null
        }
    }
}
