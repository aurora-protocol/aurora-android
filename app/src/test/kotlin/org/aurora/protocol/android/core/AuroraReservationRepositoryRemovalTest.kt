package org.aurora.protocol.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AuroraReservationRepositoryRemovalTest {
    @Test
    fun readableRemovalRetainsHistoryWithoutPurging() {
        val storage = RemovalStorage()

        repository(storage).removeProvisioning()

        assertEquals(1, storage.clearCalls)
        assertEquals(0, storage.purgeCalls)
    }

    @Test
    fun unreadableRemovalFallsBackToExplicitPurge() {
        val storage = RemovalStorage(
            clearFailure = ReservationStorageException(IllegalArgumentException("corrupted state")),
        )

        repository(storage).removeProvisioning()

        assertEquals(1, storage.clearCalls)
        assertEquals(1, storage.purgeCalls)
    }

    @Test
    fun failedRecoveryPreservesBothStorageFailures() {
        val clearFailure = ReservationStorageException(IllegalStateException("clear failed"))
        val purgeFailure = ReservationStorageException(IllegalStateException("purge failed"))
        val storage = RemovalStorage(clearFailure, purgeFailure)

        val result = assertThrows(ReservationStorageException::class.java) {
            repository(storage).removeProvisioning()
        }

        assertSame(clearFailure, result)
        assertEquals(listOf(purgeFailure), result.suppressed.toList())
        assertEquals(1, storage.clearCalls)
        assertEquals(1, storage.purgeCalls)
    }

    private fun repository(storage: ReservationStore) = AuroraReservationRepository(
        client = NativeProvisioningReservationClient { _, _ -> throw AssertionError("not used") },
        storage = storage,
    )

    private class RemovalStorage(
        private val clearFailure: ReservationStorageException? = null,
        private val purgeFailure: ReservationStorageException? = null,
    ) : ReservationStore {
        var clearCalls = 0
        var purgeCalls = 0

        override fun save(
            reservation: CoreReservation,
            sourceDigest: ByteArray,
            nowUnix: Long,
            callerSpentHintKeys: List<ByteArray>,
        ): Unit = throw AssertionError("not used")

        override fun spentHintKeys(sourceDigest: ByteArray, nowUnix: Long): List<ByteArray> =
            throw AssertionError("not used")

        override fun load(): CoreReservation? = throw AssertionError("not used")

        override fun consume(nowUnix: Long): ReservationConsumption = throw AssertionError("not used")

        override fun clear() {
            clearCalls++
            clearFailure?.let { throw it }
        }

        override fun purge() {
            purgeCalls++
            purgeFailure?.let { throw it }
        }
    }
}
