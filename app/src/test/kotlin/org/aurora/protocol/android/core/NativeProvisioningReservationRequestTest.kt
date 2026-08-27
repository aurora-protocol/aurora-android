package org.aurora.protocol.android.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeProvisioningReservationRequestTest {
    @Test
    fun hashesOnlyTheProvisioningSource() {
        val source = byteArrayOf(0x10, 0x20, 0x30)
        val firstKey = ByteArray(48) { 0x41 }
        val secondKey = ByteArray(48) { 0x42 }

        NativeProvisioningReservationRequest.takeOwnership(request(source, listOf(firstKey))).use { first ->
            NativeProvisioningReservationRequest.takeOwnership(request(source, listOf(secondKey))).use { second ->
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(source), first.sourceDigest())
                assertArrayEquals(first.sourceDigest(), second.sourceDigest())
            }
        }
    }

    @Test
    fun appendsOnlyMissingSpentHintKeys() {
        val existing = ByteArray(48) { 0x51 }
        val additional = ByteArray(48) { 0x52 }
        val original = request(byteArrayOf(0x01, 0x02), listOf(existing, existing))

        NativeProvisioningReservationRequest.takeOwnership(original).use { parsed ->
            val augmented = parsed.mergingSpentHintKeys(listOf(existing, additional, additional))
            try {
                val countOffset = Int.SIZE_BYTES + 2
                assertEquals(2, augmented[countOffset].toInt() and 0xff)
                assertEquals(Int.SIZE_BYTES + 2 + 1 + (2 * 48), augmented.size)
                NativeProvisioningReservationRequest.takeOwnership(augmented).use { merged ->
                    assertTrue(merged.containsSpentHintKey(existing))
                    assertTrue(merged.containsSpentHintKey(additional))
                    assertFalse(merged.containsSpentHintKey(ByteArray(48) { 0x53 }))
                }
            } finally {
                augmented.fill(0)
            }
        }
    }

    @Test
    fun enforcesTheExactPrivateFfiEnvelope() {
        val oversizedCount = request(byteArrayOf(0x01)).also {
            it[Int.SIZE_BYTES + 1] = 65
        }
        val full = request(byteArrayOf(0x01), List(64) { index -> ByteArray(48) { index.toByte() } })

        listOf(
            byteArrayOf(),
            byteArrayOf(0, 0, 0, 0, 0),
            byteArrayOf(0, 0, 0, 2, 0x01, 0),
            oversizedCount,
        ).forEach { malformed ->
            assertThrows(IllegalArgumentException::class.java) {
                NativeProvisioningReservationRequest.takeOwnership(malformed)
            }
            assertArrayEquals(ByteArray(malformed.size), malformed)
        }
        NativeProvisioningReservationRequest.takeOwnership(full).use { parsed ->
            val duplicateOnly = parsed.mergingSpentHintKeys(listOf(ByteArray(48)))
            try {
                assertEquals(full.size, duplicateOnly.size)
            } finally {
                duplicateOnly.fill(0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                parsed.mergingSpentHintKeys(listOf(ByteArray(48) { 0x7f }))
            }
        }
    }

    private fun request(source: ByteArray, spentHintKeys: List<ByteArray> = emptyList()): ByteArray {
        return ByteBuffer.allocate(Int.SIZE_BYTES + source.size + 1 + spentHintKeys.size * 48)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(source.size)
            .put(source)
            .put(spentHintKeys.size.toByte())
            .apply { spentHintKeys.forEach(::put) }
            .array()
    }
}
