package org.aurora.protocol.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreReservationParserTest {
    @Test
    fun decodesTheExactBinaryReservationEnvelopeAndTransfersFieldOwnership() {
        val expectedProvisioning = byteArrayOf(0x01, 0x02, 0x03)
        val expectedSpentHintKey = ByteArray(48) { it.toByte() }
        val expectedRelayBucketId = ByteArray(16) { (it + 48).toByte() }
        val encoded = encodedCoreReservation(
            expectedProvisioning,
            expectedSpentHintKey,
            expectedRelayBucketId,
            123456789,
        )
        val allocations = mutableListOf<ByteArray>()

        val reservation = CoreReservationParser.decode(encoded, allocations::add)
        try {
            assertEquals(3, allocations.size)
            assertSame(allocations[0], reservation.provisioning)
            assertSame(allocations[1], reservation.spentHintKey)
            assertSame(allocations[2], reservation.relayBucketId)
            assertArrayEquals(expectedProvisioning, reservation.provisioning)
            assertArrayEquals(expectedSpentHintKey, reservation.spentHintKey)
            assertArrayEquals(expectedRelayBucketId, reservation.relayBucketId)
            assertEquals(123456789L, reservation.accessHintExpiryUnix)
            assertArrayEquals(ByteArray(encoded.size), encoded)
        } finally {
            reservation.close()
        }

        allocations.forEach { allocation ->
            assertArrayEquals(ByteArray(allocation.size), allocation)
        }
    }

    @Test
    fun acceptsTheExactMaximumProvisioningAndSignedExpiry() {
        val encoded = encodedCoreReservation(
            provisioning = ByteArray(1024 * 1024) { index -> index.toByte() },
            expiry = Long.MAX_VALUE,
        )

        val reservation = CoreReservationParser.decode(encoded)
        try {
            assertEquals(1024 * 1024, reservation.provisioning.size)
            assertEquals(Long.MAX_VALUE, reservation.accessHintExpiryUnix)
            assertArrayEquals(ByteArray(encoded.size), encoded)
        } finally {
            reservation.close()
        }
    }

    @Test
    fun rejectsNonExactLengthsBoundsAndNonPositiveExpiryWhileClearingTheEnvelope() {
        val zeroLength = encodedCoreReservation().also {
            it[0] = 0
            it[1] = 0
            it[2] = 0
        }
        val mismatchedLength = encodedCoreReservation().also {
            it[2] = 1
        }
        val valid = encodedCoreReservation()
        val truncated = valid.copyOf(valid.size - 1)
        val trailing = valid + 0x55.toByte()
        valid.fill(0)
        val tooLarge = encodedCoreReservation(ByteArray((1024 * 1024) + 1) { 0x61 })
        val zeroExpiry = encodedCoreReservation(expiry = 0)
        val outOfSignedRangeExpiry = encodedCoreReservation(expiry = Long.MIN_VALUE)
        val malformed = listOf(
            ByteArray(75) { 0x33 },
            zeroLength,
            mismatchedLength,
            truncated,
            trailing,
            tooLarge,
            zeroExpiry,
            outOfSignedRangeExpiry,
        )

        malformed.forEach { encoded ->
            assertThrows(IllegalArgumentException::class.java) {
                CoreReservationParser.decode(encoded)
            }
            assertArrayEquals(ByteArray(encoded.size), encoded)
        }
    }

    @Test
    fun clearsEveryPartialFieldCopyWhenAnAllocationObserverFails() {
        for (failureIndex in 1..3) {
            val encoded = encodedCoreReservation()
            val allocations = mutableListOf<ByteArray>()

            assertThrows(ObservedAllocationFailure::class.java) {
                CoreReservationParser.decode(encoded) { allocation ->
                    allocations += allocation
                    if (allocations.size == failureIndex) {
                        throw ObservedAllocationFailure()
                    }
                }
            }

            assertEquals(failureIndex, allocations.size)
            allocations.forEach { allocation ->
                assertArrayEquals(ByteArray(allocation.size), allocation)
            }
            assertArrayEquals(ByteArray(encoded.size), encoded)
        }
    }

    @Test
    fun clearsAllFieldCopiesWhenValidationFailsAfterAllocation() {
        val encoded = encodedCoreReservation(expiry = 0)
        val allocations = mutableListOf<ByteArray>()

        assertThrows(IllegalArgumentException::class.java) {
            CoreReservationParser.decode(encoded, allocations::add)
        }

        assertEquals(3, allocations.size)
        assertTrue(allocations.all { allocation -> allocation.all { it == 0.toByte() } })
        assertArrayEquals(ByteArray(encoded.size), encoded)
    }

    private class ObservedAllocationFailure : RuntimeException()
}
