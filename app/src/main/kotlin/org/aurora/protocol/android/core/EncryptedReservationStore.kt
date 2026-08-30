package org.aurora.protocol.android.core

import java.security.MessageDigest

internal class EncryptedReservationStore(
    private val blobs: EncryptedReservationBlobStore,
    private val cipher: ReservationCipher,
) : ReservationStore {
    @Synchronized
    override fun save(
        reservation: CoreReservation,
        sourceDigest: ByteArray,
        nowUnix: Long,
        callerSpentHintKeys: List<ByteArray>,
    ) {
        var state: ReservationStorageState? = null
        var candidate: CoreReservation? = reservation
        val callerKeys = mutableListOf<ByteArray>()
        try {
            require(sourceDigest.size == digestBytes && nowUnix > 0) { "invalid reservation history context" }
            require(reservation.spentHintKey.size == spentHintKeyBytes) { "invalid reservation spent hint key" }
            require(reservation.accessHintExpiryUnix > nowUnix) { "expired reservation" }
            for (callerKey in callerSpentHintKeys) {
                require(callerKey.size == spentHintKeyBytes) { "invalid caller spent hint key" }
                if (callerKeys.none { MessageDigest.isEqual(it, callerKey) }) {
                    callerKeys += callerKey.copyOf()
                }
            }
            require(callerKeys.size <= maximumHistoryEntries) { "caller reservation history is too large" }
            require(callerKeys.none { MessageDigest.isEqual(it, reservation.spentHintKey) }) {
                "Core returned a caller-reserved hint"
            }
            state = loadState() ?: ReservationStorageState()
            state.prune(nowUnix)
            if (state.sourceDigest != null && !MessageDigest.isEqual(state.sourceDigest, sourceDigest)) {
                state.clearHistory()
            }
            require(!state.containsSpentHintKey(reservation.spentHintKey)) { "reservation was already recorded" }
            val missingCallerKeys = callerKeys.filterNot(state::containsSpentHintKey)
            require(state.history.size + missingCallerKeys.size + 1 <= maximumHistoryEntries) {
                "reservation history is full"
            }
            state.sourceDigest?.fill(0)
            state.sourceDigest = sourceDigest.copyOf()
            for (callerKey in callerKeys) {
                val existing = state.history.firstOrNull {
                    MessageDigest.isEqual(it.spentHintKey, callerKey)
                }
                if (existing == null) {
                    state.history += ReservationHistoryEntry(
                        spentHintKey = callerKey.copyOf(),
                        accessHintExpiryUnix = nonExpiringHistoryUnix,
                    )
                } else {
                    existing.accessHintExpiryUnix = nonExpiringHistoryUnix
                }
            }
            state.history += ReservationHistoryEntry(
                spentHintKey = reservation.spentHintKey.copyOf(),
                accessHintExpiryUnix = reservation.accessHintExpiryUnix,
            )
            state.reservation?.close()
            state.reservation = reservation
            candidate = null
            writeState(state)
        } catch (error: Exception) {
            throw storageFailure(error)
        } finally {
            // save() retains no caller-owned credential material, regardless of outcome.
            callerKeys.forEach { it.fill(0) }
            callerKeys.clear()
            candidate?.close()
            state?.close()
        }
    }

    @Synchronized
    override fun spentHintKeys(sourceDigest: ByteArray, nowUnix: Long): List<ByteArray> {
        var state: ReservationStorageState? = null
        var result: MutableList<ByteArray>? = null
        try {
            require(sourceDigest.size == digestBytes && nowUnix > 0) { "invalid reservation history context" }
            state = loadState() ?: return emptyList()
            state.prune(nowUnix)
            if (state.sourceDigest != null && !MessageDigest.isEqual(state.sourceDigest, sourceDigest)) {
                return emptyList()
            }
            result = state.history.mapTo(mutableListOf()) { it.spentHintKey.copyOf() }
            val completed = requireNotNull(result)
            result = null
            return completed
        } catch (error: Exception) {
            throw storageFailure(error)
        } finally {
            result?.forEach { it.fill(0) }
            result?.clear()
            state?.close()
        }
    }

    @Synchronized
    override fun load(): CoreReservation? {
        var state: ReservationStorageState? = null
        try {
            state = loadState() ?: return null
            return state.takeReservation()
        } catch (error: Exception) {
            throw storageFailure(error)
        } finally {
            state?.close()
        }
    }

    @Synchronized
    override fun consume(nowUnix: Long): ReservationConsumption {
        var state: ReservationStorageState? = null
        var reservation: CoreReservation? = null
        try {
            require(nowUnix > 0) { "invalid consumption time" }
            state = loadState() ?: return ReservationConsumption.Missing
            reservation = state.takeReservation() ?: return ReservationConsumption.Missing
            if (reservation.accessHintExpiryUnix <= nowUnix) {
                // The decoded copy is cleared below, but without a write the
                // persisted reservation and its replay history remain intact.
                return ReservationConsumption.Expired
            }
            // This atomic ledger-only write is the commit point. The caller never
            // receives a reservation whose spent-hint history was not retained.
            writeState(state)
            val completed = reservation
            reservation = null
            return ReservationConsumption.Available(completed)
        } catch (error: Exception) {
            throw storageFailure(error)
        } finally {
            reservation?.close()
            state?.close()
        }
    }

    @Synchronized
    override fun clear() {
        var state: ReservationStorageState? = null
        try {
            state = loadState() ?: return
            state.takeReservation()?.close() ?: return
            writeState(state)
        } catch (error: Exception) {
            throw storageFailure(error)
        } finally {
            state?.close()
        }
    }

    @Synchronized
    override fun purge() {
        try {
            blobs.clear()
        } catch (error: Exception) {
            throw storageFailure(error)
        }
    }

    private fun loadState(): ReservationStorageState? {
        val encrypted = blobs.read() ?: return null
        var plaintext: ByteArray? = null
        try {
            require(encrypted.isNotEmpty() && encrypted.size <= maximumEncryptedBytes) {
                "invalid encrypted reservation"
            }
            plaintext = cipher.decrypt(encrypted)
            require(plaintext.isNotEmpty() && plaintext.size <= maximumPlaintextBytes) {
                "invalid reservation state"
            }
            return ReservationStorageCodec.decode(plaintext)
        } finally {
            encrypted.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun writeState(state: ReservationStorageState) {
        var plaintext: ByteArray? = null
        var encrypted: ByteArray? = null
        try {
            plaintext = ReservationStorageCodec.encode(state)
            encrypted = cipher.encrypt(plaintext)
            require(encrypted.isNotEmpty() && encrypted.size <= maximumEncryptedBytes) {
                "invalid encrypted reservation"
            }
            blobs.write(encrypted)
        } finally {
            plaintext?.fill(0)
            encrypted?.fill(0)
        }
    }

    private fun storageFailure(error: Exception): ReservationStorageException {
        return if (error is ReservationStorageException) error else ReservationStorageException(error)
    }

    private companion object {
        const val maximumPlaintextBytes = (1024 * 1024) + (8 * 1024)
        const val maximumEncryptedBytes = maximumPlaintextBytes + 256
        const val digestBytes = 32
        const val spentHintKeyBytes = 48
        const val maximumHistoryEntries = 64
        const val nonExpiringHistoryUnix = Long.MAX_VALUE
    }
}
