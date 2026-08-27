package org.aurora.protocol.android.core

import java.util.Base64

internal object ProvisioningImport {
    /** Decodes [encoded] and clears the caller-owned characters before returning. */
    fun decode(encoded: CharArray): ByteArray {
        try {
            require(hasValidEncodedLength(encoded.size)) { "invalid provisioning import size" }
            val encodedBytes = ByteArray(encoded.size)
            try {
                encoded.forEachIndexed { index, character ->
                    require(character.code <= 0x7f) { "invalid provisioning import encoding" }
                    encodedBytes[index] = character.code.toByte()
                }
                val decoded = try {
                    Base64.getDecoder().decode(encodedBytes)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("invalid provisioning import encoding", error)
                }
                try {
                    require(decoded.isNotEmpty() && decoded.size <= maximumRequestBytes) { "invalid provisioning import size" }
                    val canonical = Base64.getEncoder().encode(decoded)
                    try {
                        require(canonical.contentEquals(encodedBytes)) { "non-canonical provisioning import" }
                    } finally {
                        canonical.fill(0)
                    }
                    return decoded
                } catch (error: RuntimeException) {
                    decoded.fill(0)
                    throw error
                }
            } finally {
                encodedBytes.fill(0)
            }
        } finally {
            encoded.fill('\u0000')
        }
    }

    internal fun hasValidEncodedLength(length: Int): Boolean = length in 1..maximumEncodedCharacters

    private const val maximumRequestBytes = (16 * 1024 * 1024) + 4 + 1 + (64 * 48)
    internal const val maximumEncodedCharacters = ((maximumRequestBytes + 2) / 3) * 4
}
