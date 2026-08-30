package org.aurora.protocol.android.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal object ReservationStorageCodec {
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
