package org.aurora.protocol.android.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal interface ReservationCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(ciphertext: ByteArray): ByteArray
}

internal interface EncryptedReservationBlobStore {
    fun write(encrypted: ByteArray)

    fun read(): ByteArray?

    fun clear()
}

internal interface ReservationStore {
    /** Atomically replaces the active reservation and records it as reserved. */
    fun save(
        reservation: CoreReservation,
        sourceDigest: ByteArray,
        nowUnix: Long,
        callerSpentHintKeys: List<ByteArray> = emptyList(),
    )

    /** Returns caller-owned copies of unexpired keys applicable to [sourceDigest]. */
    fun spentHintKeys(sourceDigest: ByteArray, nowUnix: Long): List<ByteArray>

    fun load(): CoreReservation?

    /** Atomically removes and returns the active reservation while retaining history. */
    fun consume(): CoreReservation?

    /** Removes only the active reservation while retaining replay history. */
    fun clear()

    /** Explicitly removes the active reservation and all replay history. */
    fun purge()
}

internal class ReservationStorageException(cause: Throwable? = null) :
    IllegalStateException("reservation storage is unavailable", cause)

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
    override fun consume(): CoreReservation? {
        var state: ReservationStorageState? = null
        var reservation: CoreReservation? = null
        try {
            state = loadState() ?: return null
            reservation = state.takeReservation() ?: return null
            // This atomic ledger-only write is the commit point. The caller never
            // receives a reservation whose spent-hint history was not retained.
            writeState(state)
            val completed = reservation
            reservation = null
            return completed
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

private class ReservationStorageState(
    var reservation: CoreReservation? = null,
    var sourceDigest: ByteArray? = null,
    val history: MutableList<ReservationHistoryEntry> = mutableListOf(),
) : AutoCloseable {
    fun containsSpentHintKey(spentHintKey: ByteArray): Boolean = history.any {
        MessageDigest.isEqual(it.spentHintKey, spentHintKey)
    }

    fun prune(nowUnix: Long) {
        val iterator = history.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.accessHintExpiryUnix <= nowUnix) {
                entry.close()
                iterator.remove()
            }
        }
        if (history.isEmpty()) {
            sourceDigest?.fill(0)
            sourceDigest = null
        }
    }

    fun clearHistory() {
        sourceDigest?.fill(0)
        sourceDigest = null
        history.forEach { it.close() }
        history.clear()
    }

    fun takeReservation(): CoreReservation? {
        val result = reservation
        reservation = null
        return result
    }

    override fun close() {
        reservation?.close()
        reservation = null
        clearHistory()
    }
}

private class ReservationHistoryEntry(
    val spentHintKey: ByteArray,
    var accessHintExpiryUnix: Long,
) : AutoCloseable {
    override fun close() {
        spentHintKey.fill(0)
    }
}

private object ReservationStorageCodec {
    private const val legacyFormat = 1
    private const val format = 2
    private const val activeReservationFlag = 1
    private const val sourceDigestFlag = 2
    private const val knownFlags = activeReservationFlag or sourceDigestFlag
    private const val maximumProvisioningBytes = 1024 * 1024
    private const val digestBytes = 32
    private const val spentHintKeyBytes = 48
    private const val relayBucketIdBytes = 16
    private const val maximumHistoryEntries = 64
    private const val entryBytes = spentHintKeyBytes + Long.SIZE_BYTES
    private const val activeHeaderBytes = Int.SIZE_BYTES
    private const val activeTrailingBytes = spentHintKeyBytes + relayBucketIdBytes + Long.SIZE_BYTES
    private const val maximumEncodedBytes = maximumProvisioningBytes + (8 * 1024)

    fun encode(state: ReservationStorageState): ByteArray {
        require(state.history.size <= maximumHistoryEntries) { "too many reservation history entries" }
        validateHistory(state.sourceDigest, state.history)
        state.reservation?.let { reservation ->
            require(state.sourceDigest != null) { "active reservation has no source binding" }
            validateReservation(reservation)
            require(state.history.any {
                it.accessHintExpiryUnix == reservation.accessHintExpiryUnix &&
                    MessageDigest.isEqual(it.spentHintKey, reservation.spentHintKey)
            }) { "active reservation is absent from history" }
        }

        val digestSize = if (state.sourceDigest == null) 0 else digestBytes
        val historySize = state.history.size * entryBytes
        val reservationSize = state.reservation?.let {
            activeHeaderBytes + it.provisioning.size + activeTrailingBytes
        } ?: 0
        val encodedSize = 3 + digestSize + historySize + reservationSize
        require(encodedSize <= maximumEncodedBytes) { "reservation state is too large" }

        val encoded = ByteBuffer.allocate(encodedSize).order(ByteOrder.BIG_ENDIAN)
        encoded.put(format.toByte())
        var flags = 0
        if (state.reservation != null) flags = flags or activeReservationFlag
        if (state.sourceDigest != null) flags = flags or sourceDigestFlag
        encoded.put(flags.toByte())
        encoded.put(state.history.size.toByte())
        state.sourceDigest?.let(encoded::put)
        for (entry in state.history) {
            encoded.put(entry.spentHintKey)
            encoded.putLong(entry.accessHintExpiryUnix)
        }
        state.reservation?.let { reservation ->
            encoded.putInt(reservation.provisioning.size)
            encoded.put(reservation.provisioning)
            encoded.put(reservation.spentHintKey)
            encoded.put(reservation.relayBucketId)
            encoded.putLong(reservation.accessHintExpiryUnix)
        }
        return encoded.array()
    }

    fun decode(encoded: ByteArray): ReservationStorageState {
        require(encoded.isNotEmpty() && encoded.size <= maximumEncodedBytes) { "invalid stored reservation size" }
        val reader = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        return when (reader.get().toInt() and 0xff) {
            legacyFormat -> decodeLegacy(reader)
            format -> decodeCurrent(reader)
            else -> throw IllegalArgumentException("stored reservation format is unsupported")
        }
    }

    private fun decodeLegacy(reader: ByteBuffer): ReservationStorageState {
        var reservation: CoreReservation? = null
        var historyKey: ByteArray? = null
        try {
            reservation = decodeReservation(reader)
            require(!reader.hasRemaining()) { "stored reservation has trailing bytes" }
            historyKey = reservation.spentHintKey.copyOf()
            val state = ReservationStorageState(
                reservation = reservation,
                sourceDigest = null,
                history = mutableListOf(
                    ReservationHistoryEntry(historyKey, reservation.accessHintExpiryUnix),
                ),
            )
            reservation = null
            historyKey = null
            return state
        } finally {
            reservation?.close()
            historyKey?.fill(0)
        }
    }

    private fun decodeCurrent(reader: ByteBuffer): ReservationStorageState {
        var reservation: CoreReservation? = null
        var sourceDigest: ByteArray? = null
        val history = mutableListOf<ReservationHistoryEntry>()
        try {
            require(reader.remaining() >= 2) { "stored reservation state is truncated" }
            val flags = reader.get().toInt() and 0xff
            require(flags and knownFlags == flags) { "stored reservation flags are invalid" }
            val historyCount = reader.get().toInt() and 0xff
            require(historyCount <= maximumHistoryEntries) { "stored reservation history count is invalid" }
            if (flags and sourceDigestFlag != 0) {
                require(reader.remaining() >= digestBytes) { "stored reservation source digest is truncated" }
                sourceDigest = ByteArray(digestBytes).also(reader::get)
            }
            require(reader.remaining() >= historyCount * entryBytes) { "stored reservation history is truncated" }
            repeat(historyCount) {
                val key = ByteArray(spentHintKeyBytes).also(reader::get)
                val expiry = reader.long
                if (expiry <= 0) {
                    key.fill(0)
                    throw IllegalArgumentException("stored reservation history expiry is invalid")
                }
                history += ReservationHistoryEntry(key, expiry)
            }
            if (flags and activeReservationFlag != 0) {
                reservation = decodeReservation(reader)
            }
            require(!reader.hasRemaining()) { "stored reservation state has trailing bytes" }
            validateHistory(sourceDigest, history)
            reservation?.let { active ->
                require(sourceDigest != null) { "active reservation has no source binding" }
                require(history.any {
                    it.accessHintExpiryUnix == active.accessHintExpiryUnix &&
                        MessageDigest.isEqual(it.spentHintKey, active.spentHintKey)
                }) { "active reservation is absent from history" }
            }
            val completedHistory = history.toMutableList()
            history.clear()
            val state = ReservationStorageState(reservation, sourceDigest, completedHistory)
            reservation = null
            sourceDigest = null
            return state
        } finally {
            reservation?.close()
            sourceDigest?.fill(0)
            history.forEach { it.close() }
            history.clear()
        }
    }

    private fun decodeReservation(reader: ByteBuffer): CoreReservation {
        var provisioning: ByteArray? = null
        var spentHintKey: ByteArray? = null
        var relayBucketId: ByteArray? = null
        try {
            require(reader.remaining() >= activeHeaderBytes + activeTrailingBytes) {
                "stored reservation is truncated"
            }
            val provisioningLength = reader.int
            require(provisioningLength in 1..maximumProvisioningBytes) { "stored provisioning length is invalid" }
            require(reader.remaining() >= provisioningLength + activeTrailingBytes) {
                "stored reservation length is invalid"
            }
            provisioning = ByteArray(provisioningLength).also(reader::get)
            spentHintKey = ByteArray(spentHintKeyBytes).also(reader::get)
            relayBucketId = ByteArray(relayBucketIdBytes).also(reader::get)
            val expiry = reader.long
            require(expiry > 0) { "stored reservation expiry is invalid" }
            return CoreReservation(provisioning, spentHintKey, relayBucketId, expiry)
        } catch (error: RuntimeException) {
            provisioning?.fill(0)
            spentHintKey?.fill(0)
            relayBucketId?.fill(0)
            throw IllegalArgumentException("invalid stored reservation", error)
        }
    }

    private fun validateReservation(reservation: CoreReservation) {
        require(reservation.provisioning.isNotEmpty() && reservation.provisioning.size <= maximumProvisioningBytes) {
            "invalid stored provisioning"
        }
        require(reservation.spentHintKey.size == spentHintKeyBytes) { "invalid stored spent hint key" }
        require(reservation.relayBucketId.size == relayBucketIdBytes) { "invalid stored relay bucket identifier" }
        require(reservation.accessHintExpiryUnix > 0) { "invalid stored reservation expiry" }
    }

    private fun validateHistory(sourceDigest: ByteArray?, history: List<ReservationHistoryEntry>) {
        require(sourceDigest == null || sourceDigest.size == digestBytes) { "invalid reservation source digest" }
        require(sourceDigest == null || history.isNotEmpty()) { "empty source-bound reservation history" }
        require(sourceDigest != null || history.size <= 1) { "unbound reservation history is invalid" }
        require(history.size <= maximumHistoryEntries) { "too many reservation history entries" }
        for ((index, entry) in history.withIndex()) {
            require(entry.spentHintKey.size == spentHintKeyBytes && entry.accessHintExpiryUnix > 0) {
                "invalid reservation history entry"
            }
            require(history.take(index).none { MessageDigest.isEqual(it.spentHintKey, entry.spentHintKey) }) {
                "duplicate reservation history entry"
            }
        }
    }
}
