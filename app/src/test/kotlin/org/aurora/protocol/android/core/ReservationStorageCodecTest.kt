package org.aurora.protocol.android.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReservationStorageCodecTest {
    @Test
    fun roundTripsASourceBoundStateWithAnActiveReservation() {
        val sourceDigest = ByteArray(32) { 0x21 }
        val historyKey = ByteArray(48) { 0x31 }
        val activeKey = ByteArray(48) { 0x32 }
        val state = ReservationStorageState(
            reservation = reservation(spentHintKey = activeKey, expiry = 600),
            sourceDigest = sourceDigest.copyOf(),
            history = mutableListOf(
                ReservationHistoryEntry(historyKey.copyOf(), 500),
                ReservationHistoryEntry(activeKey.copyOf(), 600),
            ),
        )
        val encoded = ReservationStorageCodec.encode(state)
        state.close()

        val decoded = ReservationStorageCodec.decode(encoded)
        try {
            assertArrayEquals(sourceDigest, decoded.sourceDigest)
            assertEquals(2, decoded.history.size)
            assertArrayEquals(historyKey, decoded.history[0].spentHintKey)
            assertEquals(500, decoded.history[0].accessHintExpiryUnix)
            assertArrayEquals(activeKey, decoded.history[1].spentHintKey)
            assertEquals(600, decoded.history[1].accessHintExpiryUnix)
            val restored = requireNotNull(decoded.reservation)
            assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), restored.provisioning)
            assertArrayEquals(activeKey, restored.spentHintKey)
            assertArrayEquals(ByteArray(16) { (it + 48).toByte() }, restored.relayBucketId)
            assertEquals(600, restored.accessHintExpiryUnix)
        } finally {
            decoded.close()
        }
    }

    @Test
    fun roundTripsSourceBoundHistoryWithoutAnActiveReservation() {
        val sourceDigest = ByteArray(32) { 0x22 }
        val historyKey = ByteArray(48) { 0x33 }
        val state = ReservationStorageState(
            sourceDigest = sourceDigest.copyOf(),
            history = mutableListOf(ReservationHistoryEntry(historyKey.copyOf(), 500)),
        )
        val encoded = ReservationStorageCodec.encode(state)
        state.close()

        val decoded = ReservationStorageCodec.decode(encoded)
        try {
            assertNull(decoded.reservation)
            assertArrayEquals(sourceDigest, decoded.sourceDigest)
            assertEquals(1, decoded.history.size)
            assertArrayEquals(historyKey, decoded.history[0].spentHintKey)
            assertEquals(500, decoded.history[0].accessHintExpiryUnix)
        } finally {
            decoded.close()
        }
    }

    @Test
    fun rejectsTruncatedInputAtEveryFieldBoundary() {
        val state = sourceBoundState()
        val encoded = ReservationStorageCodec.encode(state)
        state.close()
        val legacy = legacyReservation(spentHintKey = ByteArray(48) { 0x61 }, expiry = 500)
        val truncations = listOf(
            encoded.copyOf(1),
            encoded.copyOf(20),
            encoded.copyOf(100),
            encoded.copyOf(encoded.size - 1),
            legacy.copyOf(legacy.size - 1),
        )

        truncations.forEach { truncated ->
            assertThrows(IllegalArgumentException::class.java) {
                ReservationStorageCodec.decode(truncated)
            }
        }
        legacy.fill(0)
    }

    @Test
    fun rejectsTrailingBytesInBothFormats() {
        val state = sourceBoundState()
        val encoded = ReservationStorageCodec.encode(state)
        state.close()
        val legacy = legacyReservation(spentHintKey = ByteArray(48) { 0x62 }, expiry = 500)

        assertThrows(IllegalArgumentException::class.java) {
            ReservationStorageCodec.decode(encoded + 0x55.toByte())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReservationStorageCodec.decode(legacy + 0x55.toByte())
        }
        legacy.fill(0)
    }

    @Test
    fun rejectsUnsupportedFormatsAndInvalidSizes() {
        assertThrows(IllegalArgumentException::class.java) {
            ReservationStorageCodec.decode(ByteArray(0))
        }
        listOf(0x00, 0x03, 0x7f).forEach { formatByte ->
            assertThrows(IllegalArgumentException::class.java) {
                ReservationStorageCodec.decode(byteArrayOf(formatByte.toByte()))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReservationStorageCodec.decode(ByteArray((1024 * 1024) + (8 * 1024) + 1))
        }
    }

    @Test
    fun rejectsDuplicateHistoryEntriesOnDecodeAndEncode() {
        val duplicateKey = ByteArray(48) { 0x51 }
        val encoded = ByteBuffer.allocate(3 + 32 + 2 * (48 + Long.SIZE_BYTES))
            .order(ByteOrder.BIG_ENDIAN)
            .put(2.toByte())
            .put(2.toByte())
            .put(2.toByte())
            .put(ByteArray(32) { 0x52 })
            .put(duplicateKey)
            .putLong(500)
            .put(duplicateKey)
            .putLong(600)
            .array()

        assertThrows(IllegalArgumentException::class.java) {
            ReservationStorageCodec.decode(encoded)
        }

        val state = ReservationStorageState(
            sourceDigest = ByteArray(32) { 0x52 },
            history = mutableListOf(
                ReservationHistoryEntry(duplicateKey.copyOf(), 500),
                ReservationHistoryEntry(duplicateKey.copyOf(), 600),
            ),
        )
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ReservationStorageCodec.encode(state)
            }
        } finally {
            state.close()
        }
    }

    @Test
    fun decodesLegacyReservationOnlyBlobs() {
        val legacyKey = ByteArray(48) { 0x61 }
        val legacy = legacyReservation(spentHintKey = legacyKey, expiry = 500)

        val decoded = ReservationStorageCodec.decode(legacy)
        try {
            assertNull(decoded.sourceDigest)
            assertEquals(1, decoded.history.size)
            assertArrayEquals(legacyKey, decoded.history[0].spentHintKey)
            assertEquals(500, decoded.history[0].accessHintExpiryUnix)
            val restored = requireNotNull(decoded.reservation)
            assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), restored.provisioning)
            assertArrayEquals(legacyKey, restored.spentHintKey)
            assertArrayEquals(ByteArray(16) { (it + 48).toByte() }, restored.relayBucketId)
            assertEquals(500, restored.accessHintExpiryUnix)
        } finally {
            decoded.close()
        }
    }

    @Test
    fun rejectsAnActiveReservationAbsentFromHistoryAndLeavesTheCallerBufferIntact() {
        // The reservation decodes before the history binding check fails, so this
        // exercises the failure path that must clear its decoded copies. Those
        // copies are internal; the observable guarantee is that the caller-owned
        // input is never consumed or destroyed by a failed decode (the store
        // clears it itself).
        val historyKey = ByteArray(48) { 0x71 }
        val activeKey = ByteArray(48) { 0x72 }
        val provisioning = byteArrayOf(0x01, 0x02, 0x03)
        val encoded = ByteBuffer.allocate(3 + 32 + (48 + Long.SIZE_BYTES) + Int.SIZE_BYTES + provisioning.size + 48 + 16 + Long.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(2.toByte())
            .put(3.toByte())
            .put(1.toByte())
            .put(ByteArray(32) { 0x73 })
            .put(historyKey)
            .putLong(500)
            .putInt(provisioning.size)
            .put(provisioning)
            .put(activeKey)
            .put(ByteArray(16) { (it + 48).toByte() })
            .putLong(500)
            .array()
        val before = encoded.copyOf()

        assertThrows(IllegalArgumentException::class.java) {
            ReservationStorageCodec.decode(encoded)
        }

        assertArrayEquals(before, encoded)
        before.fill(0)
    }

    @Test
    fun closingADecodedStateClearsEverySensitiveBuffer() {
        val state = sourceBoundState()
        val encoded = ReservationStorageCodec.encode(state)
        state.close()
        val decoded = ReservationStorageCodec.decode(encoded)
        val provisioning = requireNotNull(decoded.reservation).provisioning
        val spentHintKey = requireNotNull(decoded.reservation).spentHintKey
        val relayBucketId = requireNotNull(decoded.reservation).relayBucketId
        val sourceDigest = requireNotNull(decoded.sourceDigest)
        val historyKeys = decoded.history.map { it.spentHintKey }

        decoded.close()

        assertArrayEquals(ByteArray(provisioning.size), provisioning)
        assertArrayEquals(ByteArray(spentHintKey.size), spentHintKey)
        assertArrayEquals(ByteArray(relayBucketId.size), relayBucketId)
        assertArrayEquals(ByteArray(sourceDigest.size), sourceDigest)
        historyKeys.forEach { assertArrayEquals(ByteArray(it.size), it) }
    }

    private fun sourceBoundState(): ReservationStorageState {
        val activeKey = ByteArray(48) { 0x32 }
        return ReservationStorageState(
            reservation = reservation(spentHintKey = activeKey, expiry = 600),
            sourceDigest = ByteArray(32) { 0x21 },
            history = mutableListOf(
                ReservationHistoryEntry(ByteArray(48) { 0x31 }, 500),
                ReservationHistoryEntry(activeKey.copyOf(), 600),
            ),
        )
    }

    private fun reservation(spentHintKey: ByteArray, expiry: Long): CoreReservation {
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
}
