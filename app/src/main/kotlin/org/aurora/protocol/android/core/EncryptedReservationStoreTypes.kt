package org.aurora.protocol.android.core

internal interface ReservationCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(ciphertext: ByteArray): ByteArray
}

internal interface EncryptedReservationBlobStore {
    fun write(encrypted: ByteArray)

    fun read(): ByteArray?

    fun clear()
}

internal sealed interface ReservationConsumption {
    data class Available(val reservation: CoreReservation) : ReservationConsumption

    data object Missing : ReservationConsumption

    data object Expired : ReservationConsumption
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

    /** Atomically classifies the active entry and consumes it only while unexpired. */
    fun consume(nowUnix: Long): ReservationConsumption

    /** Removes only the active reservation while retaining replay history. */
    fun clear()

    /** Explicitly removes the active reservation and all replay history. */
    fun purge()
}

internal class ReservationStorageException(cause: Throwable? = null) :
    IllegalStateException("reservation storage is unavailable", cause)
