package org.aurora.protocol.android.core

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal class AuroraReservationRepository(
    private val client: NativeProvisioningReservationClient,
    private val storage: ReservationStore,
) {
    private val reservationInProgress = AtomicBoolean(false)

    fun reserveAndPersist(request: ByteArray, issuedAtUnix: Long): Long {
        if (!reservationInProgress.compareAndSet(false, true)) {
            request.fill(0)
            throw IllegalStateException("provisioning reservation is already in progress")
        }
        var reservationRequest: NativeProvisioningReservationRequest? = null
        var sourceDigest: ByteArray? = null
        var callerSpentHintKeys: List<ByteArray> = emptyList()
        var persistedSpentHintKeys: List<ByteArray> = emptyList()
        var augmentedRequest: ByteArray? = null
        var reservation: CoreReservation? = null
        var persistedExpiryUnix: Long? = null
        try {
            synchronized(this) {
                // This check occurs after acquiring the repository monitor so a
                // rotation-interrupted caller cannot wait behind consume/load and
                // then reserve stale input once that operation finishes.
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("provisioning reservation was cancelled")
                }
                require(issuedAtUnix > 0) { "invalid reservation time" }
                val parsedRequest = NativeProvisioningReservationRequest.takeOwnership(request)
                reservationRequest = parsedRequest
                val digest = parsedRequest.sourceDigest()
                sourceDigest = digest
                callerSpentHintKeys = parsedRequest.spentHintKeys()
                persistedSpentHintKeys = storage.spentHintKeys(digest, issuedAtUnix)
                val mergedRequest = parsedRequest.mergingSpentHintKeys(persistedSpentHintKeys)
                augmentedRequest = mergedRequest
                val reserved = client.reserve(mergedRequest, issuedAtUnix)
                reservation = reserved
                require(reserved.accessHintExpiryUnix > issuedAtUnix) { "expired Core reservation" }
                require(!parsedRequest.containsSpentHintKey(reserved.spentHintKey)) {
                    "Core returned a caller-reserved hint"
                }
                require(persistedSpentHintKeys.none { MessageDigest.isEqual(it, reserved.spentHintKey) }) {
                    "Core returned a persisted reservation"
                }
                storage.save(reserved, digest, issuedAtUnix, callerSpentHintKeys)
                persistedExpiryUnix = reserved.accessHintExpiryUnix
                reservation = null
            }
        } finally {
            reservation?.close()
            augmentedRequest?.fill(0)
            callerSpentHintKeys.forEach { it.fill(0) }
            persistedSpentHintKeys.forEach { it.fill(0) }
            sourceDigest?.fill(0)
            reservationRequest?.close()
            request.fill(0)
            reservationInProgress.set(false)
        }
        return requireNotNull(persistedExpiryUnix)
    }

    @Synchronized
    fun load(): CoreReservation? = storage.load()

    /** Classifies the active entry without consuming it and clears the decrypted copy immediately. */
    @Synchronized
    fun storedReservationAvailability(nowUnix: Long): StoredReservationAvailability {
        require(nowUnix > 0) { "invalid availability time" }
        return storage.load()?.use { reservation ->
            if (reservation.accessHintExpiryUnix > nowUnix) {
                StoredReservationAvailability.Available(reservation.accessHintExpiryUnix)
            } else {
                StoredReservationAvailability.Expired
            }
        } ?: StoredReservationAvailability.Missing
    }

    @Synchronized
    fun consume(nowUnix: Long): ReservationConsumption {
        require(nowUnix > 0) { "invalid consumption time" }
        return storage.consume(nowUnix)
    }

    @Synchronized
    fun clear() {
        storage.clear()
    }

    /**
     * Preserves replay history when readable, but lets an explicit user removal
     * recover from ciphertext or keystore failures that make normal clear impossible.
     */
    @Synchronized
    fun removeProvisioning() {
        try {
            storage.clear()
        } catch (clearFailure: ReservationStorageException) {
            try {
                storage.purge()
            } catch (purgeFailure: ReservationStorageException) {
                if (clearFailure !== purgeFailure) {
                    clearFailure.addSuppressed(purgeFailure)
                }
                throw clearFailure
            }
        }
    }

    @Synchronized
    fun purge() {
        storage.purge()
    }
}

internal sealed interface StoredReservationAvailability {
    data class Available(val expiryUnix: Long) : StoredReservationAvailability {
        init {
            require(expiryUnix > 0) { "invalid reservation expiry" }
        }
    }

    data object Missing : StoredReservationAvailability

    data object Expired : StoredReservationAvailability
}
