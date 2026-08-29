package org.aurora.protocol.android.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedReservationStoreTest {
    @Test
    fun encryptsReservationsAndTransfersOwnershipToTheStore() {
        val blobs = MemoryBlobStore()
        val store = EncryptedReservationStore(blobs, InvertingCipher)
        val reservation = reservation()

        store.save(reservation, ByteArray(32) { 0x31 }, 100)

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
    fun preservesCorruptedPersistedHistoryForExplicitRecovery() {
        val blobs = MemoryBlobStore(byteArrayOf(0x7f))
        val store = EncryptedReservationStore(blobs, object : ReservationCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.copyOf()

            override fun decrypt(ciphertext: ByteArray): ByteArray = byteArrayOf(0x00)
        })

        assertThrows(ReservationStorageException::class.java) {
            store.load()
        }

        assertNotNull(blobs.value)
    }

    @Test
    fun consumeAndNormalClearRetainSourceBoundHistory() {
        val digest = ByteArray(32) { 0x21 }
        val blobs = MemoryBlobStore()
        val store = EncryptedReservationStore(blobs, InvertingCipher)
        val firstKey = ByteArray(48) { 0x31 }
        store.save(reservation(spentHintKey = firstKey, expiry = 500), digest, 100)

        store.consumeAvailable(100).close()

        assertNull(store.load())
        assertHistory(store, digest, 100, firstKey)

        val secondKey = ByteArray(48) { 0x32 }
        store.save(reservation(spentHintKey = secondKey, expiry = 600), digest, 100)
        store.clear()

        assertNull(store.load())
        assertHistory(store, digest, 100, firstKey, secondKey)
        assertNotNull(blobs.value)

        store.purge()

        assertNull(blobs.value)
        assertTrue(store.spentHintKeys(digest, 100).isEmpty())
    }

    @Test
    fun prunesExpiredEntriesAndResetsHistoryWhenTheSourceChanges() {
        val firstDigest = ByteArray(32) { 0x41 }
        val secondDigest = ByteArray(32) { 0x42 }
        val blobs = MemoryBlobStore()
        val store = EncryptedReservationStore(blobs, InvertingCipher)
        val firstKey = ByteArray(48) { 0x51 }
        val secondKey = ByteArray(48) { 0x52 }
        store.save(reservation(spentHintKey = firstKey, expiry = 500), firstDigest, 100)
        store.consumeAvailable(100).close()

        assertHistory(store, firstDigest, 149, firstKey)

        store.save(reservation(spentHintKey = secondKey, expiry = 300), secondDigest, 200)
        store.consumeAvailable(200).close()

        assertTrue(store.spentHintKeys(firstDigest, 200).isEmpty())
        assertHistory(store, secondDigest, 200, secondKey)
        assertTrue(store.spentHintKeys(secondDigest, 300).isEmpty())
    }

    @Test
    fun migratesLegacyReservationOnlyBlobsBeforeConsumption() {
        val legacyKey = ByteArray(48) { 0x61 }
        val legacyPlaintext = legacyReservation(spentHintKey = legacyKey, expiry = 500)
        val blobs = MemoryBlobStore(InvertingCipher.encrypt(legacyPlaintext))
        legacyPlaintext.fill(0)
        val store = EncryptedReservationStore(blobs, InvertingCipher)

        store.consumeAvailable(100).close()

        val migrated = requireNotNull(blobs.value).let(InvertingCipher::decrypt)
        try {
            assertEquals(2, migrated[0].toInt() and 0xff)
        } finally {
            migrated.fill(0)
        }
        // A legacy reservation did not carry its source digest. Its one key is
        // conservatively applied to the next validated source and bound on save.
        val newDigest = ByteArray(32) { 0x62 }
        assertHistory(store, newDigest, 100, legacyKey)
        val newKey = ByteArray(48) { 0x63 }
        store.save(reservation(spentHintKey = newKey, expiry = 600), newDigest, 100)
        store.consumeAvailable(100).close()
        assertHistory(store, newDigest, 100, legacyKey, newKey)
        assertTrue(store.spentHintKeys(ByteArray(32) { 0x64 }, 100).isEmpty())
    }

    @Test
    fun failedConsumeDoesNotReturnOrDeleteTheActiveReservation() {
        val digest = ByteArray(32) { 0x71 }
        val blobs = MemoryBlobStore()
        val store = EncryptedReservationStore(blobs, InvertingCipher)
        val key = ByteArray(48) { 0x72 }
        store.save(reservation(spentHintKey = key, expiry = 500), digest, 100)
        blobs.writeFailure = IllegalStateException("atomic write failed")

        assertThrows(ReservationStorageException::class.java) {
            store.consume(100)
        }

        blobs.writeFailure = null
        val stillActive = requireNotNull(store.load())
        try {
            assertArrayEquals(key, stillActive.spentHintKey)
        } finally {
            stillActive.close()
        }
        assertHistory(store, digest, 100, key)
    }

    @Test
    fun expiredConsumeReturnsNothingWithoutRewritingOrRemovingTheActiveReservation() {
        val digest = ByteArray(32) { 0x73 }
        val blobs = MemoryBlobStore()
        val store = EncryptedReservationStore(blobs, InvertingCipher)
        val key = ByteArray(48) { 0x74 }
        store.save(reservation(spentHintKey = key, expiry = 500), digest, 100)
        val before = requireNotNull(blobs.value).copyOf()

        assertEquals(ReservationConsumption.Expired, store.consume(500))

        assertArrayEquals(before, blobs.value)
        before.fill(0)
        val stillActive = requireNotNull(store.load())
        try {
            assertArrayEquals(key, stillActive.spentHintKey)
            assertEquals(500, stillActive.accessHintExpiryUnix)
        } finally {
            stillActive.close()
        }
        assertHistory(store, digest, 100, key)
    }

    @Test
    fun failedCombinedSaveClosesTheCandidateAndLeavesNoUnledgeredState() {
        val blobs = MemoryBlobStore().apply {
            writeFailure = IllegalStateException("atomic write failed")
        }
        val store = EncryptedReservationStore(blobs, InvertingCipher)
        val callerKey = ByteArray(48) { 0x79 }
        val candidate = reservation(spentHintKey = ByteArray(48) { 0x7a }, expiry = 500)

        assertThrows(ReservationStorageException::class.java) {
            store.save(candidate, ByteArray(32) { 0x7b }, 100, listOf(callerKey, callerKey))
        }

        assertArrayEquals(ByteArray(candidate.provisioning.size), candidate.provisioning)
        assertArrayEquals(ByteArray(candidate.spentHintKey.size), candidate.spentHintKey)
        assertNull(blobs.value)
    }

    @Test
    fun rejectsAndClearsProvisioningLargerThanThePinnedCoreContract() {
        val store = EncryptedReservationStore(MemoryBlobStore(), InvertingCipher)
        val oversized = CoreReservation(
            provisioning = ByteArray((1024 * 1024) + 1) { 0x41 },
            spentHintKey = ByteArray(48) { it.toByte() },
            relayBucketId = ByteArray(16) { it.toByte() },
            accessHintExpiryUnix = 500,
        )

        assertThrows(ReservationStorageException::class.java) {
            store.save(oversized, ByteArray(32), 100)
        }

        assertArrayEquals(ByteArray(oversized.provisioning.size), oversized.provisioning)
        assertArrayEquals(ByteArray(oversized.spentHintKey.size), oversized.spentHintKey)
        assertArrayEquals(ByteArray(oversized.relayBucketId.size), oversized.relayBucketId)
    }

    @Test
    fun deduplicatesCallerHintsAndCapsTheirUnionWithTheCoreResult() {
        val digest = ByteArray(32) { 0x11 }
        val store = EncryptedReservationStore(MemoryBlobStore(), InvertingCipher)
        val callerKeys = List(63) { index -> ByteArray(48) { index.toByte() } }
        val firstResultKey = ByteArray(48) { 0x7e }
        store.save(
            reservation(spentHintKey = firstResultKey, expiry = 500),
            digest,
            100,
            callerKeys + callerKeys.first(),
        )
        store.consumeAvailable(100).close()
        val overflow = reservation(spentHintKey = ByteArray(48) { 0x7f }, expiry = 500)

        assertThrows(ReservationStorageException::class.java) {
            store.save(overflow, digest, 100, callerKeys)
        }

        assertArrayEquals(ByteArray(overflow.spentHintKey.size), overflow.spentHintKey)
        val history = store.spentHintKeys(digest, 100)
        try {
            assertEquals(64, history.size)
        } finally {
            history.forEach { it.fill(0) }
        }
        val afterResultExpiry = store.spentHintKeys(digest, 500)
        try {
            assertEquals(63, afterResultExpiry.size)
            callerKeys.indices.forEach { assertArrayEquals(callerKeys[it], afterResultExpiry[it]) }
        } finally {
            afterResultExpiry.forEach { it.fill(0) }
        }
    }

    @Test
    fun storageFailuresPreserveTheirCauseWithoutRevealingItInTheMessage() {
        val failure = IllegalStateException("keystore unavailable")
        val blobs = FailingBlobStore(failure)
        val store = EncryptedReservationStore(blobs, InvertingCipher)

        val saveError = assertThrows(ReservationStorageException::class.java) {
            store.save(reservation(), ByteArray(32), 100)
        }
        assertSame(failure, saveError.cause)

        val loadError = assertThrows(ReservationStorageException::class.java) {
            store.load()
        }
        assertSame(failure, loadError.cause)

        val clearError = assertThrows(ReservationStorageException::class.java) {
            store.clear()
        }
        assertSame(failure, clearError.cause)

        val purgeError = assertThrows(ReservationStorageException::class.java) {
            store.purge()
        }
        assertSame(failure, purgeError.cause)

        // The message stays constant: this is credential storage, so the cause is
        // available for diagnostics but must never reach a caller-visible string.
        assertEquals("reservation storage is unavailable", saveError.message)
        assertEquals("reservation storage is unavailable", loadError.message)
        assertEquals("reservation storage is unavailable", clearError.message)
    }

    private class FailingBlobStore(private val failure: RuntimeException) : EncryptedReservationBlobStore {
        override fun write(encrypted: ByteArray): Unit = throw failure

        override fun read(): ByteArray = throw failure

        override fun clear(): Unit = throw failure
    }

    private fun reservation(
        spentHintKey: ByteArray = ByteArray(48) { it.toByte() },
        expiry: Long = 123,
    ): CoreReservation {
        return CoreReservation(
            provisioning = byteArrayOf(0x01, 0x02, 0x03),
            spentHintKey = spentHintKey.copyOf(),
            relayBucketId = ByteArray(16) { (it + 48).toByte() },
            accessHintExpiryUnix = expiry,
        )
    }

    private fun legacyReservation(spentHintKey: ByteArray, expiry: Long): ByteArray {
        val provisioning = byteArrayOf(0x01, 0x02, 0x03)
        return ByteBuffer.allocate(1 + Int.SIZE_BYTES + provisioning.size + 48 + 16 + Long.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(1.toByte())
            .putInt(provisioning.size)
            .put(provisioning)
            .put(spentHintKey)
            .put(ByteArray(16) { (it + 48).toByte() })
            .putLong(expiry)
            .array()
    }

    private fun assertHistory(
        store: ReservationStore,
        sourceDigest: ByteArray,
        nowUnix: Long,
        vararg expected: ByteArray,
    ) {
        val actual = store.spentHintKeys(sourceDigest, nowUnix)
        try {
            assertEquals(expected.size, actual.size)
            expected.indices.forEach { assertArrayEquals(expected[it], actual[it]) }
        } finally {
            actual.forEach { it.fill(0) }
        }
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
        var writeFailure: RuntimeException? = null

        override fun write(encrypted: ByteArray) {
            writeFailure?.let { throw it }
            value = encrypted.copyOf()
        }

        override fun read(): ByteArray? = value?.copyOf()

        override fun clear() {
            value?.fill(0)
            value = null
        }
    }

    private fun ReservationStore.consumeAvailable(nowUnix: Long): CoreReservation =
        when (val consumption = consume(nowUnix)) {
            is ReservationConsumption.Available -> consumption.reservation
            ReservationConsumption.Missing, ReservationConsumption.Expired -> {
                throw AssertionError("expected available reservation, got $consumption")
            }
        }
}
