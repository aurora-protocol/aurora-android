package org.aurora.protocol.android.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    fun save(reservation: CoreReservation)

    fun load(): CoreReservation?

    fun clear()
}

internal class ReservationStorageException : IllegalStateException("reservation storage is unavailable")

internal class EncryptedReservationStore(
    private val blobs: EncryptedReservationBlobStore,
    private val cipher: ReservationCipher,
) : ReservationStore {
    @Synchronized
    override fun save(reservation: CoreReservation) {
        var plaintext: ByteArray? = null
        var encrypted: ByteArray? = null
        try {
            plaintext = ReservationStorageCodec.encode(reservation)
            encrypted = cipher.encrypt(plaintext)
            require(encrypted.isNotEmpty() && encrypted.size <= maximumEncryptedBytes) { "invalid encrypted reservation" }
            blobs.write(encrypted)
        } catch (_: Exception) {
            throw ReservationStorageException()
        } finally {
            reservation.close()
            plaintext?.fill(0)
            encrypted?.fill(0)
        }
    }

    @Synchronized
    override fun load(): CoreReservation? {
        val encrypted = try {
            blobs.read()
        } catch (_: Exception) {
            throw ReservationStorageException()
        } ?: return null
        var plaintext: ByteArray? = null
        try {
            require(encrypted.isNotEmpty() && encrypted.size <= maximumEncryptedBytes) { "invalid encrypted reservation" }
            plaintext = cipher.decrypt(encrypted)
            return ReservationStorageCodec.decode(plaintext)
        } catch (_: Exception) {
            try {
                blobs.clear()
            } catch (_: Exception) {
                // A later load must still fail closed if this corrupt blob cannot be removed.
            }
            throw ReservationStorageException()
        } finally {
            encrypted.fill(0)
            plaintext?.fill(0)
        }
    }

    @Synchronized
    override fun clear() {
        try {
            blobs.clear()
        } catch (_: Exception) {
            throw ReservationStorageException()
        }
    }

    private companion object {
        const val maximumEncryptedBytes = (16 * 1024 * 1024) + 128
    }
}

private object ReservationStorageCodec {
    private const val format = 1
    private const val maximumProvisioningBytes = 16 * 1024 * 1024
    private const val spentHintKeyBytes = 48
    private const val relayBucketIdBytes = 16
    private const val headerBytes = 1 + Int.SIZE_BYTES
    private const val trailingBytes = spentHintKeyBytes + relayBucketIdBytes + Long.SIZE_BYTES

    fun encode(reservation: CoreReservation): ByteArray {
        require(reservation.provisioning.isNotEmpty() && reservation.provisioning.size <= maximumProvisioningBytes) {
            "invalid stored provisioning"
        }
        require(reservation.spentHintKey.size == spentHintKeyBytes) { "invalid stored spent hint key" }
        require(reservation.relayBucketId.size == relayBucketIdBytes) { "invalid stored relay bucket identifier" }
        require(reservation.accessHintExpiryUnix > 0) { "invalid stored reservation expiry" }

        return ByteBuffer.allocate(headerBytes + reservation.provisioning.size + trailingBytes)
            .order(ByteOrder.BIG_ENDIAN)
            .put(format.toByte())
            .putInt(reservation.provisioning.size)
            .put(reservation.provisioning)
            .put(reservation.spentHintKey)
            .put(reservation.relayBucketId)
            .putLong(reservation.accessHintExpiryUnix)
            .array()
    }

    fun decode(encoded: ByteArray): CoreReservation {
        var provisioning: ByteArray? = null
        var spentHintKey: ByteArray? = null
        var relayBucketId: ByteArray? = null
        try {
            val reader = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
            require(reader.remaining() >= headerBytes + trailingBytes) { "stored reservation is truncated" }
            require(reader.get().toInt() and 0xff == format) { "stored reservation format is unsupported" }
            val provisioningLength = reader.int
            require(provisioningLength in 1..maximumProvisioningBytes) { "stored provisioning length is invalid" }
            require(reader.remaining() == provisioningLength + trailingBytes) { "stored reservation length is invalid" }
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
}
