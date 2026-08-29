package org.aurora.protocol.android.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraReservationRepositoryTest {
    @Test
    fun reservesAndTransfersTheResultToEncryptedStorage() {
        val responsePayload = encodedReservation()
        val response = CoreResponse(CoreStatus.OK, responsePayload)
        val core = NativeProvisioningReservationClient { _, _ -> response }
        val storage = RecordingStorage()
        val repository = AuroraReservationRepository(core, storage)

        val expiryUnix = repository.reserveAndPersist(reservationRequest(byteArrayOf(0x01)), 122)

        assertEquals(123L, expiryUnix)
        assertArrayEquals(byteArrayOf(0x01, 0x02), storage.provisioning)
        assertArrayEquals(ByteArray(responsePayload.size), responsePayload)
    }

    @Test
    fun consumesStoredProvisioningBeforeStartingANativeSession() {
        val storage = ConsumingStorage()
        val repository = AuroraReservationRepository(
            NativeProvisioningReservationClient { _, _ -> throw AssertionError("not used") },
            storage,
        )

        val reservation = repository.consumeAvailable(122)
        try {
            assertArrayEquals(byteArrayOf(0x01, 0x02), reservation?.provisioning)
            assertTrue(storage.cleared)
        } finally {
            reservation?.close()
        }
    }

    @Test
    fun usableAvailabilityCheckDoesNotConsumeStorageAndClearsItsDecryptedCopy() {
        val storage = ConsumingStorage()
        val repository = AuroraReservationRepository(
            NativeProvisioningReservationClient { _, _ -> throw AssertionError("not used") },
            storage,
        )

        assertEquals(
            StoredReservationAvailability.Available(123),
            repository.storedReservationAvailability(122),
        )

        assertFalse(storage.cleared)
        assertArrayEquals(ByteArray(2), storage.lastLoadedProvisioning)
        assertArrayEquals(ByteArray(48), storage.lastLoadedSpentHintKey)
        assertArrayEquals(ByteArray(16), storage.lastLoadedRelayBucketId)
        repository.consumeAvailable(122)?.use { reservation ->
            assertArrayEquals(byteArrayOf(0x01, 0x02), reservation.provisioning)
        }
        assertTrue(storage.cleared)
    }

    @Test
    fun expiredAvailabilityCheckFailsClosedWithoutConsumingStorage() {
        val storage = ConsumingStorage()
        val repository = AuroraReservationRepository(
            NativeProvisioningReservationClient { _, _ -> throw AssertionError("not used") },
            storage,
        )

        assertEquals(StoredReservationAvailability.Expired, repository.storedReservationAvailability(123))

        assertFalse(storage.cleared)
        assertArrayEquals(ByteArray(2), storage.lastLoadedProvisioning)
        assertArrayEquals(ByteArray(48), storage.lastLoadedSpentHintKey)
        assertArrayEquals(ByteArray(16), storage.lastLoadedRelayBucketId)
    }

    @Test
    fun expiredConsumptionReturnsNothingWithoutClearingStorage() {
        val storage = ConsumingStorage()
        val repository = AuroraReservationRepository(
            NativeProvisioningReservationClient { _, _ -> throw AssertionError("not used") },
            storage,
        )

        assertEquals(ReservationConsumption.Expired, repository.consume(123))

        assertFalse(storage.cleared)
        assertArrayEquals(ByteArray(2), storage.lastLoadedProvisioning)
        assertArrayEquals(ByteArray(48), storage.lastLoadedSpentHintKey)
        assertArrayEquals(ByteArray(16), storage.lastLoadedRelayBucketId)
    }

    @Test
    fun serializesOneTimeConsumptionAcrossConcurrentCallers() {
        val storage = CoordinatedStorage()
        val repository = AuroraReservationRepository(
            NativeProvisioningReservationClient { _, _ -> throw AssertionError("not used") },
            storage,
        )
        val results = arrayOfNulls<CoreReservation>(2)
        val failures = arrayOfNulls<Throwable>(2)
        val first = Thread {
            try {
                results[0] = repository.consumeAvailable(122)
            } catch (error: Throwable) {
                failures[0] = error
            }
        }
        var second: Thread? = null
        try {
            first.start()
            assertTrue(storage.firstLoadEntered.await(2, TimeUnit.SECONDS))
            val secondThread = Thread {
                try {
                    results[1] = repository.consumeAvailable(122)
                } catch (error: Throwable) {
                    failures[1] = error
                }
            }
            second = secondThread
            secondThread.start()

            val secondWasSerialized = waitForBlockedCaller(secondThread, storage.secondLoadEntered)
            storage.releaseFirstLoad.countDown()
            first.join(2_000)
            secondThread.join(2_000)

            assertTrue(secondWasSerialized)
            assertFalse(first.isAlive)
            assertFalse(secondThread.isAlive)
            failures.forEach { assertNull(it) }
            assertEquals(1, results.count { it != null })
        } finally {
            storage.releaseFirstLoad.countDown()
            first.join(5_000)
            second?.join(5_000)
            results.forEach { it?.close() }
        }
    }

    @Test
    fun interruptedImportFailsBeforeCoreAndClearsItsRequest() {
        val request = byteArrayOf(0x01, 0x02)
        val coreCalls = AtomicInteger()
        val repository = AuroraReservationRepository(
            NativeProvisioningReservationClient { _, _ ->
                coreCalls.incrementAndGet()
                throw AssertionError("interrupted import reached Core")
            },
            RecordingStorage(),
        )

        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) {
                repository.reserveAndPersist(request, 123)
            }
        } finally {
            Thread.interrupted()
        }

        assertEquals(0, coreCalls.get())
        assertArrayEquals(ByteArray(request.size), request)
    }

    @Test
    fun rejectsASecondReservationWhileAnActivityPredecessorIsStillWorking() {
        val firstEnteredCore = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val coreCalls = AtomicInteger()
        val client = NativeProvisioningReservationClient { _, _ ->
            coreCalls.incrementAndGet()
            firstEnteredCore.countDown()
            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
            CoreResponse(CoreStatus.OK, encodedReservation())
        }
        val repository = AuroraReservationRepository(client, RecordingStorage())
        val firstRequest = reservationRequest(byteArrayOf(0x01))
        val secondRequest = reservationRequest(byteArrayOf(0x02))
        val firstFailure = arrayOfNulls<Throwable>(1)
        val first = Thread {
            try {
                repository.reserveAndPersist(firstRequest, 122)
            } catch (error: Throwable) {
                firstFailure[0] = error
            }
        }

        try {
            first.start()
            assertTrue(firstEnteredCore.await(2, TimeUnit.SECONDS))

            assertThrows(IllegalStateException::class.java) {
                repository.reserveAndPersist(secondRequest, 122)
            }

            assertEquals(1, coreCalls.get())
            assertArrayEquals(ByteArray(secondRequest.size), secondRequest)
        } finally {
            releaseFirst.countDown()
            first.join(5_000)
        }

        assertFalse(first.isAlive)
        assertNull(firstFailure[0])
        assertArrayEquals(ByteArray(firstRequest.size), firstRequest)
    }

    @Test
    fun identicalWalletReimportPassesDurableHistoryBackToCore() {
        val blobs = RepositoryMemoryBlobStore()
        val storage = EncryptedReservationStore(blobs, RepositoryCipher)
        val firstKey = ByteArray(48) { 0x41 }
        val secondKey = ByteArray(48) { 0x42 }
        val seenFirstKey = mutableListOf<Boolean>()
        val client = NativeProvisioningReservationClient { request, _ ->
            NativeProvisioningReservationRequest.takeOwnership(request).use { parsed ->
                val hasFirst = parsed.containsSpentHintKey(firstKey)
                seenFirstKey += hasFirst
                CoreResponse(
                    CoreStatus.OK,
                    encodedReservation(
                        provisioning = if (hasFirst) byteArrayOf(0x02) else byteArrayOf(0x01),
                        spentHintKey = if (hasFirst) secondKey else firstKey,
                        expiry = 500,
                    ),
                )
            }
        }
        val repository = AuroraReservationRepository(client, storage)
        val source = byteArrayOf(0x11, 0x22)

        repository.reserveAndPersist(reservationRequest(source), 100)
        val first = requireNotNull(repository.consumeAvailable(100))
        try {
            assertArrayEquals(firstKey, first.spentHintKey)
        } finally {
            first.close()
        }

        repository.reserveAndPersist(reservationRequest(source), 100)
        val second = requireNotNull(repository.consumeAvailable(100))
        try {
            assertArrayEquals(secondKey, second.spentHintKey)
        } finally {
            second.close()
        }

        assertEquals(listOf(false, true), seenFirstKey)
    }

    @Test
    fun callerHintsRemainSpentWhenAReimportOmitsThem() {
        val blobs = RepositoryMemoryBlobStore()
        val storage = EncryptedReservationStore(blobs, RepositoryCipher)
        val callerKey = ByteArray(48) { 0x31 }
        val firstResultKey = ByteArray(48) { 0x32 }
        val secondResultKey = ByteArray(48) { 0x33 }
        val source = byteArrayOf(0x21, 0x22)
        var call = 0
        val repository = AuroraReservationRepository(
            NativeProvisioningReservationClient { request, _ ->
                NativeProvisioningReservationRequest.takeOwnership(request).use { parsed ->
                    when (call++) {
                        0 -> {
                            assertTrue(parsed.containsSpentHintKey(callerKey))
                            assertFalse(parsed.containsSpentHintKey(firstResultKey))
                            CoreResponse(
                                CoreStatus.OK,
                                encodedReservation(spentHintKey = firstResultKey, expiry = 500),
                            )
                        }
                        else -> {
                            assertTrue(parsed.containsSpentHintKey(callerKey))
                            assertTrue(parsed.containsSpentHintKey(firstResultKey))
                            CoreResponse(
                                CoreStatus.OK,
                                encodedReservation(spentHintKey = secondResultKey, expiry = 600),
                            )
                        }
                    }
                }
            },
            storage,
        )
        val firstRequest = reservationRequest(source, listOf(callerKey))

        repository.reserveAndPersist(firstRequest, 100)
        repository.consumeAvailable(100)?.close()
        repository.reserveAndPersist(reservationRequest(source), 100)
        repository.consumeAvailable(100)?.close()

        assertArrayEquals(ByteArray(firstRequest.size), firstRequest)
        assertEquals(2, call)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(source)
        val durable = storage.spentHintKeys(digest, 501)
        try {
            assertEquals(2, durable.size)
            assertTrue(durable.any { it.contentEquals(callerKey) })
            assertTrue(durable.any { it.contentEquals(secondResultKey) })
            assertFalse(durable.any { it.contentEquals(firstResultKey) })
        } finally {
            durable.forEach { it.fill(0) }
            digest.fill(0)
        }
    }

    @Test
    fun deduplicatesCallerAndPersistedHintsBeforeCallingCore() {
        val source = byteArrayOf(0x31)
        val persistedKey = ByteArray(48) { 0x51 }
        val callerKey = ByteArray(48) { 0x52 }
        val resultKey = ByteArray(48) { 0x53 }
        val storage = EncryptedReservationStore(RepositoryMemoryBlobStore(), RepositoryCipher)
        NativeProvisioningReservationRequest.takeOwnership(reservationRequest(source)).use { parsed ->
            storage.save(
                reservation(spentHintKey = persistedKey, expiry = 500),
                parsed.sourceDigest(),
                100,
            )
        }
        storage.consumeAvailable(100).close()
        var receivedCount = -1
        val repository = AuroraReservationRepository(
            NativeProvisioningReservationClient { request, _ ->
                receivedCount = request[Int.SIZE_BYTES + source.size].toInt() and 0xff
                CoreResponse(
                    CoreStatus.OK,
                    encodedReservation(spentHintKey = resultKey, expiry = 500),
                )
            },
            storage,
        )

        repository.reserveAndPersist(reservationRequest(source, listOf(persistedKey, callerKey)), 100)

        assertEquals(2, receivedCount)
    }

    @Test
    fun rejectsACoreResultThatWasAlreadyPersisted() {
        val source = byteArrayOf(0x61)
        val spentHintKey = ByteArray(48) { 0x62 }
        val storage = EncryptedReservationStore(RepositoryMemoryBlobStore(), RepositoryCipher)
        NativeProvisioningReservationRequest.takeOwnership(reservationRequest(source)).use { parsed ->
            storage.save(
                reservation(spentHintKey = spentHintKey, expiry = 500),
                parsed.sourceDigest(),
                100,
            )
        }
        storage.consumeAvailable(100).close()
        val repository = AuroraReservationRepository(
            NativeProvisioningReservationClient { _, _ ->
                CoreResponse(
                    CoreStatus.OK,
                    encodedReservation(spentHintKey = spentHintKey, expiry = 500),
                )
            },
            storage,
        )

        assertThrows(IllegalArgumentException::class.java) {
            repository.reserveAndPersist(reservationRequest(source), 100)
        }

        assertNull(storage.load())
    }

    @Test
    fun callerHintCommitFailureScrubsInputAndReturnsNoReservation() {
        val blobs = RepositoryMemoryBlobStore().apply {
            writeFailure = IllegalStateException("atomic write failed")
        }
        val responsePayload = encodedReservation(
            spentHintKey = ByteArray(48) { 0x72 },
            expiry = 500,
        )
        val repository = AuroraReservationRepository(
            NativeProvisioningReservationClient { _, _ -> CoreResponse(CoreStatus.OK, responsePayload) },
            EncryptedReservationStore(blobs, RepositoryCipher),
        )
        val request = reservationRequest(
            source = byteArrayOf(0x71),
            spentHintKeys = listOf(ByteArray(48) { 0x70 }),
        )

        assertThrows(ReservationStorageException::class.java) {
            repository.reserveAndPersist(request, 100)
        }

        assertArrayEquals(ByteArray(request.size), request)
        assertArrayEquals(ByteArray(responsePayload.size), responsePayload)
        assertNull(blobs.read())
    }

    @Test
    fun rejectsInvalidTimesAndExpiredCoreResultsWithoutPersisting() {
        var coreCalls = 0
        val repository = AuroraReservationRepository(
            NativeProvisioningReservationClient { _, _ ->
                coreCalls += 1
                CoreResponse(CoreStatus.OK, encodedReservation(expiry = 100))
            },
            EncryptedReservationStore(RepositoryMemoryBlobStore(), RepositoryCipher),
        )
        val mistimedRequest = reservationRequest(byteArrayOf(0x71))

        assertThrows(IllegalArgumentException::class.java) {
            repository.reserveAndPersist(mistimedRequest, 0)
        }

        assertEquals(0, coreCalls)
        assertArrayEquals(ByteArray(mistimedRequest.size), mistimedRequest)

        val responsePayload = encodedReservation(expiry = 100)
        val expiring = AuroraReservationRepository(
            NativeProvisioningReservationClient { _, _ -> CoreResponse(CoreStatus.OK, responsePayload) },
            EncryptedReservationStore(RepositoryMemoryBlobStore(), RepositoryCipher),
        )
        val request = reservationRequest(byteArrayOf(0x72))

        val error = assertThrows(IllegalArgumentException::class.java) {
            expiring.reserveAndPersist(request, 100)
        }

        assertEquals("expired Core reservation", error.message)
        assertArrayEquals(ByteArray(request.size), request)
        assertArrayEquals(ByteArray(responsePayload.size), responsePayload)
        assertNull(expiring.load())
    }

    @Test
    fun rejectsACoreResultAlreadyNamedByTheCallerEnvelope() {
        val callerKey = ByteArray(48) { 0x66 }
        val storage = EncryptedReservationStore(RepositoryMemoryBlobStore(), RepositoryCipher)
        val repository = AuroraReservationRepository(
            NativeProvisioningReservationClient { _, _ ->
                CoreResponse(
                    CoreStatus.OK,
                    encodedReservation(spentHintKey = callerKey, expiry = 500),
                )
            },
            storage,
        )
        val request = reservationRequest(byteArrayOf(0x65), listOf(callerKey))

        assertThrows(IllegalArgumentException::class.java) {
            repository.reserveAndPersist(request, 100)
        }

        assertArrayEquals(ByteArray(request.size), request)
        assertNull(storage.load())
    }

    private fun encodedReservation(
        provisioning: ByteArray = byteArrayOf(0x01, 0x02),
        spentHintKey: ByteArray = ByteArray(48),
        expiry: Long = 123,
    ): ByteArray = encodedCoreReservation(
        provisioning = provisioning,
        spentHintKey = spentHintKey,
        relayBucketId = ByteArray(16),
        expiry = expiry,
    )

    private fun reservation(spentHintKey: ByteArray, expiry: Long): CoreReservation {
        return CoreReservation(
            provisioning = byteArrayOf(0x01),
            spentHintKey = spentHintKey.copyOf(),
            relayBucketId = ByteArray(16),
            accessHintExpiryUnix = expiry,
        )
    }

    private class RecordingStorage : ReservationStore {
        var provisioning = ByteArray(0)

        override fun save(
            reservation: CoreReservation,
            sourceDigest: ByteArray,
            nowUnix: Long,
            callerSpentHintKeys: List<ByteArray>,
        ) {
            provisioning = reservation.provisioning.copyOf()
            reservation.close()
        }

        override fun spentHintKeys(sourceDigest: ByteArray, nowUnix: Long): List<ByteArray> = emptyList()

        override fun load(): CoreReservation? = null

        override fun consume(nowUnix: Long): ReservationConsumption = ReservationConsumption.Missing

        override fun clear() = Unit

        override fun purge() = Unit
    }

    private class ConsumingStorage : ReservationStore {
        var cleared = false
        var lastLoadedProvisioning = ByteArray(0)
        var lastLoadedSpentHintKey = ByteArray(0)
        var lastLoadedRelayBucketId = ByteArray(0)

        override fun save(
            reservation: CoreReservation,
            sourceDigest: ByteArray,
            nowUnix: Long,
            callerSpentHintKeys: List<ByteArray>,
        ) = reservation.close()

        override fun spentHintKeys(sourceDigest: ByteArray, nowUnix: Long): List<ByteArray> = emptyList()

        override fun load(): CoreReservation {
            val reservation = CoreReservation(
                provisioning = byteArrayOf(0x01, 0x02),
                spentHintKey = ByteArray(48) { 0x03 },
                relayBucketId = ByteArray(16) { 0x04 },
                accessHintExpiryUnix = 123,
            )
            lastLoadedProvisioning = reservation.provisioning
            lastLoadedSpentHintKey = reservation.spentHintKey
            lastLoadedRelayBucketId = reservation.relayBucketId
            return reservation
        }

        override fun clear() {
            cleared = true
        }

        override fun consume(nowUnix: Long): ReservationConsumption {
            val reservation = load()
            if (reservation.accessHintExpiryUnix <= nowUnix) {
                reservation.close()
                return ReservationConsumption.Expired
            }
            clear()
            return ReservationConsumption.Available(reservation)
        }

        override fun purge() = clear()
    }

    private class CoordinatedStorage : ReservationStore {
        val firstLoadEntered = CountDownLatch(1)
        val releaseFirstLoad = CountDownLatch(1)
        val secondLoadEntered = CountDownLatch(1)
        private val loadCount = AtomicInteger()

        @Volatile
        private var available = true

        override fun save(
            reservation: CoreReservation,
            sourceDigest: ByteArray,
            nowUnix: Long,
            callerSpentHintKeys: List<ByteArray>,
        ) = reservation.close()

        override fun spentHintKeys(sourceDigest: ByteArray, nowUnix: Long): List<ByteArray> = emptyList()

        override fun load(): CoreReservation? {
            val wasAvailable = available
            when (loadCount.incrementAndGet()) {
                1 -> {
                    firstLoadEntered.countDown()
                    assertTrue(releaseFirstLoad.await(5, TimeUnit.SECONDS))
                }
                2 -> secondLoadEntered.countDown()
            }
            if (!wasAvailable) {
                return null
            }
            return CoreReservation(
                provisioning = byteArrayOf(0x01),
                spentHintKey = ByteArray(48),
                relayBucketId = ByteArray(16),
                accessHintExpiryUnix = 123,
            )
        }

        override fun clear() {
            available = false
        }

        override fun consume(nowUnix: Long): ReservationConsumption {
            val reservation = load() ?: return ReservationConsumption.Missing
            if (reservation.accessHintExpiryUnix <= nowUnix) {
                reservation.close()
                return ReservationConsumption.Expired
            }
            clear()
            return ReservationConsumption.Available(reservation)
        }

        override fun purge() = clear()
    }

    private object RepositoryCipher : ReservationCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = transform(plaintext)

        override fun decrypt(ciphertext: ByteArray): ByteArray = transform(ciphertext)

        private fun transform(input: ByteArray): ByteArray = ByteArray(input.size) { index ->
            (input[index].toInt() xor 0x5a).toByte()
        }
    }

    private class RepositoryMemoryBlobStore : EncryptedReservationBlobStore {
        private var value: ByteArray? = null
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

    private fun AuroraReservationRepository.consumeAvailable(nowUnix: Long): CoreReservation? =
        when (val consumption = consume(nowUnix)) {
            is ReservationConsumption.Available -> consumption.reservation
            ReservationConsumption.Missing, ReservationConsumption.Expired -> null
        }

    private fun ReservationStore.consumeAvailable(nowUnix: Long): CoreReservation =
        when (val consumption = consume(nowUnix)) {
            is ReservationConsumption.Available -> consumption.reservation
            ReservationConsumption.Missing, ReservationConsumption.Expired -> {
                throw AssertionError("expected available reservation, got $consumption")
            }
        }

    private fun waitForBlockedCaller(thread: Thread, enteredStorage: CountDownLatch): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (thread.state == Thread.State.BLOCKED) {
                return true
            }
            if (enteredStorage.count == 0L) {
                return false
            }
            Thread.yield()
        }
        return thread.state == Thread.State.BLOCKED
    }

    private fun reservationRequest(source: ByteArray, spentHintKeys: List<ByteArray> = emptyList()): ByteArray {
        return ByteBuffer.allocate(Int.SIZE_BYTES + source.size + 1 + spentHintKeys.size * 48)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(source.size)
            .put(source)
            .put(spentHintKeys.size.toByte())
            .apply { spentHintKeys.forEach(::put) }
            .array()
    }
}
