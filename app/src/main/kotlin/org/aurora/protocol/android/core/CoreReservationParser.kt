package org.aurora.protocol.android.core

internal class CoreReservation(
    val provisioning: ByteArray,
    val spentHintKey: ByteArray,
    val relayBucketId: ByteArray,
    val accessHintExpiryUnix: Long,
) : AutoCloseable {
    override fun close() {
        provisioning.fill(0)
        spentHintKey.fill(0)
        relayBucketId.fill(0)
    }
}

internal object CoreReservationParser {
    /** Takes ownership of [encoded] and clears it before returning or throwing. */
    fun decode(encoded: ByteArray): CoreReservation = decode(encoded, null)

    /** Test seam for observing field copies that must be cleared after a later failure. */
    internal fun decode(
        encoded: ByteArray,
        allocationObserver: ((ByteArray) -> Unit)?,
    ): CoreReservation {
        var provisioning: ByteArray? = null
        var spentHintKey: ByteArray? = null
        var relayBucketId: ByteArray? = null
        try {
            require(encoded.size in minimumReservationResultBytes..maximumReservationResultBytes) {
                "invalid reservation result size"
            }
            val provisioningLength =
                ((encoded[0].toInt() and 0xff) shl 16) or
                    ((encoded[1].toInt() and 0xff) shl 8) or
                    (encoded[2].toInt() and 0xff)
            require(provisioningLength in 1..maximumNativeProvisioningBytes) {
                "invalid provisioning length"
            }
            require(encoded.size == provisioningLengthBytes + provisioningLength + trailingMetadataBytes) {
                "invalid reservation result length"
            }

            var offset = provisioningLengthBytes
            provisioning = encoded.copyOfRange(offset, offset + provisioningLength)
            allocationObserver?.invoke(provisioning)
            offset += provisioningLength
            spentHintKey = encoded.copyOfRange(offset, offset + spentHintKeyBytes)
            allocationObserver?.invoke(spentHintKey)
            offset += spentHintKeyBytes
            relayBucketId = encoded.copyOfRange(offset, offset + relayBucketIdBytes)
            allocationObserver?.invoke(relayBucketId)
            offset += relayBucketIdBytes
            val expiry = readLong(encoded, offset)
            require(expiry > 0) { "invalid reservation expiry" }

            val reservation = CoreReservation(
                provisioning = requireNotNull(provisioning),
                spentHintKey = requireNotNull(spentHintKey),
                relayBucketId = requireNotNull(relayBucketId),
                accessHintExpiryUnix = expiry,
            )
            provisioning = null
            spentHintKey = null
            relayBucketId = null
            return reservation
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("invalid Core reservation", error)
        } finally {
            encoded.fill(0)
            provisioning?.fill(0)
            spentHintKey?.fill(0)
            relayBucketId?.fill(0)
        }
    }

    private fun readLong(encoded: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(Long.SIZE_BYTES) { index ->
            value = (value shl Byte.SIZE_BITS) or (encoded[offset + index].toLong() and 0xff)
        }
        return value
    }

    private const val provisioningLengthBytes = 3
    private const val maximumNativeProvisioningBytes = 1024 * 1024
    private const val spentHintKeyBytes = 48
    private const val relayBucketIdBytes = 16
    private const val trailingMetadataBytes = spentHintKeyBytes + relayBucketIdBytes + Long.SIZE_BYTES
    private const val minimumReservationResultBytes = provisioningLengthBytes + 1 + trailingMetadataBytes
    private const val maximumReservationResultBytes =
        provisioningLengthBytes + maximumNativeProvisioningBytes + trailingMetadataBytes
}
