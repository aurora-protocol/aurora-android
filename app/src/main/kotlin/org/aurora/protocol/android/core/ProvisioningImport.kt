package org.aurora.protocol.android.core

import java.util.Base64

internal object ProvisioningImport {
    fun decode(encoded: String): ByteArray {
        require(encoded.isNotEmpty() && encoded.length <= maximumEncodedCharacters) { "invalid provisioning import size" }
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("invalid provisioning import encoding", error)
        }
        try {
            require(decoded.isNotEmpty() && decoded.size <= maximumRequestBytes) { "invalid provisioning import size" }
            require(Base64.getEncoder().encodeToString(decoded) == encoded) { "non-canonical provisioning import" }
            return decoded
        } catch (error: RuntimeException) {
            decoded.fill(0)
            throw error
        }
    }

    private const val maximumRequestBytes = (16 * 1024 * 1024) + 4 + 1 + (64 * 48)
    private const val maximumEncodedCharacters = ((maximumRequestBytes + 2) / 3) * 4
}
