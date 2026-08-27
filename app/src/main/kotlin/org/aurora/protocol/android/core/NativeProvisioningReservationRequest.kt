package org.aurora.protocol.android.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Parses and augments the private Core mobile-FFI reservation envelope.
 *
 * This is not an Aurora network message. The envelope contains one encoded
 * provisioning source followed by the spent-hint keys Core must skip while
 * selecting a wallet entry.
 */
internal class NativeProvisioningReservationRequest private constructor(
    private val encoded: ByteArray,
    private val sourceLength: Int,
    private val spentHintKeyCount: Int,
) : AutoCloseable {
    fun sourceDigest(): ByteArray {
        val digest = MessageDigest.getInstance(digestAlgorithm)
        digest.update(encoded, sourceOffset, sourceLength)
        return digest.digest()
    }

    /** Returns caller-owned, deduplicated copies of the envelope's hint keys. */
    fun spentHintKeys(): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var offset = spentHintKeysOffset
        repeat(spentHintKeyCount) {
            val key = encoded.copyOfRange(offset, offset + spentHintKeyBytes)
            if (result.any { MessageDigest.isEqual(it, key) }) {
                key.fill(0)
            } else {
                result += key
            }
            offset += spentHintKeyBytes
        }
        return result
    }

    fun mergingSpentHintKeys(additionalKeys: List<ByteArray>): ByteArray {
        val mergedKeys = spentHintKeys().toMutableList()
        try {
            for (candidate in additionalKeys) {
                require(candidate.size == spentHintKeyBytes) { "invalid persisted spent hint key" }
                if (mergedKeys.none { MessageDigest.isEqual(it, candidate) }) {
                    mergedKeys += candidate.copyOf()
                }
            }
            require(mergedKeys.size <= maximumSpentHintKeys) {
                "too many spent hint keys"
            }
            val result = ByteArray(spentHintKeysOffset + mergedKeys.size * spentHintKeyBytes)
            System.arraycopy(encoded, 0, result, 0, spentHintKeyCountOffset)
            result[spentHintKeyCountOffset] = mergedKeys.size.toByte()
            var outputOffset = spentHintKeysOffset
            for (key in mergedKeys) {
                System.arraycopy(key, 0, result, outputOffset, key.size)
                outputOffset += key.size
            }
            return result
        } finally {
            mergedKeys.forEach { it.fill(0) }
            mergedKeys.clear()
        }
    }

    fun containsSpentHintKey(candidate: ByteArray): Boolean {
        if (candidate.size != spentHintKeyBytes) {
            return false
        }
        var offset = spentHintKeysOffset
        repeat(spentHintKeyCount) {
            var difference = 0
            for (index in candidate.indices) {
                difference = difference or ((encoded[offset + index].toInt() and 0xff) xor (candidate[index].toInt() and 0xff))
            }
            if (difference == 0) {
                return true
            }
            offset += spentHintKeyBytes
        }
        return false
    }

    override fun close() {
        encoded.fill(0)
    }

    private val spentHintKeyCountOffset: Int
        get() = sourceOffset + sourceLength

    private val spentHintKeysOffset: Int
        get() = spentHintKeyCountOffset + countBytes

    companion object {
        private const val sourceLengthBytes = Int.SIZE_BYTES
        private const val sourceOffset = sourceLengthBytes
        private const val countBytes = 1
        private const val spentHintKeyBytes = 48
        private const val maximumSourceBytes = 16 * 1024 * 1024
        private const val maximumSpentHintKeys = 64
        private const val maximumRequestBytes =
            maximumSourceBytes + sourceLengthBytes + countBytes + maximumSpentHintKeys * spentHintKeyBytes
        private const val digestAlgorithm = "SHA-256"

        /** Takes ownership of [request]; [close] clears it. */
        fun takeOwnership(request: ByteArray): NativeProvisioningReservationRequest {
            try {
                require(request.size in (sourceLengthBytes + countBytes)..maximumRequestBytes) {
                    "invalid reservation request size"
                }
                val sourceLength = ByteBuffer.wrap(request, 0, sourceLengthBytes)
                    .order(ByteOrder.BIG_ENDIAN)
                    .int
                require(sourceLength in 1..maximumSourceBytes) { "invalid reservation source length" }
                require(sourceLength <= request.size - sourceLengthBytes - countBytes) {
                    "truncated reservation source"
                }
                val countOffset = sourceOffset + sourceLength
                val spentHintKeyCount = request[countOffset].toInt() and 0xff
                require(spentHintKeyCount <= maximumSpentHintKeys) { "invalid spent hint key count" }
                val expectedSize = countOffset + countBytes + spentHintKeyCount * spentHintKeyBytes
                require(request.size == expectedSize) { "invalid reservation request length" }
                return NativeProvisioningReservationRequest(
                    encoded = request,
                    sourceLength = sourceLength,
                    spentHintKeyCount = spentHintKeyCount,
                )
            } catch (error: RuntimeException) {
                request.fill(0)
                throw error
            }
        }
    }
}
