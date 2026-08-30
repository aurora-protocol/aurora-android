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
                    require(isCanonicalBase64(encodedBytes)) { "non-canonical provisioning import" }
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

    private fun isCanonicalBase64(encoded: ByteArray): Boolean {
        if (encoded.isEmpty() || encoded.size % 4 != 0) {
            return false
        }
        val padding = when {
            encoded[encoded.lastIndex] != base64Padding -> 0
            encoded[encoded.lastIndex - 1] == base64Padding -> 2
            else -> 1
        }
        val contentEnd = encoded.size - padding
        for (index in 0 until contentEnd) {
            if (base64Value(encoded[index]) < 0) {
                return false
            }
        }
        for (index in contentEnd until encoded.size) {
            if (encoded[index] != base64Padding) {
                return false
            }
        }
        return when (padding) {
            2 -> contentEnd % 4 == 2 && base64Value(encoded[contentEnd - 1]) and 0x0f == 0
            1 -> contentEnd % 4 == 3 && base64Value(encoded[contentEnd - 1]) and 0x03 == 0
            else -> true
        }
    }

    private fun base64Value(value: Byte): Int = when (val unsigned = value.toInt() and 0xff) {
        in 'A'.code..'Z'.code -> unsigned - 'A'.code
        in 'a'.code..'z'.code -> unsigned - 'a'.code + 26
        in '0'.code..'9'.code -> unsigned - '0'.code + 52
        '+'.code -> 62
        '/'.code -> 63
        else -> -1
    }

    private const val maximumRequestBytes = (16 * 1024 * 1024) + 4 + 1 + (64 * 48)
    internal const val maximumEncodedCharacters = ((maximumRequestBytes + 2) / 3) * 4
    private val base64Padding = '='.code.toByte()
}
