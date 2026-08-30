package org.aurora.protocol.android.core

import java.io.InputStream

internal fun HttpsIssuerExchange.readBounded(
    input: InputStream,
    startedAtNanos: Long,
    declaredLength: Long,
): ByteArray {
    val initialCapacity = if (declaredLength > 0) {
        declaredLength.toInt()
    } else {
        HttpsIssuerExchange.initialResponseBytes
    }
    var result = ByteArray(initialCapacity)
    var resultLength = 0
    var resultTransferred = false
    val chunk = ByteArray(HttpsIssuerExchange.readChunkBytes)
    try {
        while (true) {
            ensureBeforeDeadline(startedAtNanos)
            val read = input.read(chunk)
            ensureBeforeDeadline(startedAtNanos)
            if (read < 0) {
                break
            }
            if (read == 0) {
                continue
            }
            require(read <= HttpsIssuerExchange.maximumIssuerResponseBytes - resultLength) {
                "issuer response exceeds size limit"
            }
            if (resultLength + read > result.size) {
                val grownSize = minOf(
                    HttpsIssuerExchange.maximumIssuerResponseBytes,
                    maxOf(result.size * 2, resultLength + read),
                )
                val grown = ByteArray(grownSize)
                result.copyInto(grown, endIndex = resultLength)
                result.fill(0)
                result = grown
            }
            chunk.copyInto(result, destinationOffset = resultLength, endIndex = read)
            resultLength += read
        }
        require(resultLength > 0) { "issuer response is empty" }
        return if (resultLength == result.size) {
            resultTransferred = true
            result
        } else {
            result.copyOf(resultLength)
        }
    } finally {
        if (!resultTransferred) {
            result.fill(0)
        }
        chunk.fill(0)
    }
}

internal fun isBinaryContentType(value: String?): Boolean {
    val mediaType = value?.substringBefore(';')?.trim()
    return mediaType.equals("application/octet-stream", ignoreCase = true)
}

internal fun combineFailures(first: Throwable?, next: Throwable): Throwable {
    if (first == null) {
        return next
    }
    if (first !== next) {
        first.addSuppressed(next)
    }
    return first
}
