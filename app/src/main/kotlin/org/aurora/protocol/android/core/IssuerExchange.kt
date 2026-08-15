package org.aurora.protocol.android.core

import java.io.InputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal interface IssuerExchange {
    fun exchange(work: NativeIssuerWork): ByteArray
}

internal interface CancellableIssuerExchange : IssuerExchange {
    fun cancel()
}

internal data class IssuerHttpResponse(
    val statusCode: Int,
    val contentLength: Long,
    val body: InputStream?,
    val contentType: String?,
)

internal interface IssuerHttpConnection : AutoCloseable {
    fun post(requestBody: ByteArray): IssuerHttpResponse
}

internal fun interface IssuerHttpConnectionFactory {
    fun open(endpoint: URL): IssuerHttpConnection
}

internal class HttpsIssuerExchange(
    private val connectionFactory: IssuerHttpConnectionFactory = IssuerHttpConnectionFactory { endpoint ->
        HttpsIssuerHttpConnection(endpoint)
    },
) : CancellableIssuerExchange {
    private val exchangeLock = Any()
    private var activeConnection: IssuerHttpConnection? = null
    private var cancelled = false

    override fun exchange(work: NativeIssuerWork): ByteArray {
        require(work.handle > 0) { "invalid issuer handle" }
        require(work.requestBody.isNotEmpty() && work.requestBody.size <= maximumIssuerRequestBytes) {
            "invalid issuer request body"
        }
        val endpoint = endpointFor(work)
        val connection = connectionFactory.open(endpoint)
        val accepted = synchronized(exchangeLock) {
            if (cancelled) {
                false
            } else {
                activeConnection = connection
                true
            }
        }
        if (!accepted) {
            connection.close()
            throw IllegalStateException("issuer exchange was cancelled")
        }
        try {
            val response = connection.post(work.requestBody)
            require(response.statusCode == HttpsURLConnection.HTTP_OK) { "issuer rejected request" }
            require(isBinaryContentType(response.contentType)) { "issuer response content type is invalid" }
            require(response.contentLength in -1..maximumIssuerResponseBytes.toLong()) { "issuer response exceeds size limit" }
            val body = response.body ?: throw IllegalStateException("issuer response body is unavailable")
            return body.use { readBounded(it) }
        } finally {
            synchronized(exchangeLock) {
                if (activeConnection === connection) {
                    activeConnection = null
                }
            }
            connection.close()
        }
    }

    override fun cancel() {
        val connection = synchronized(exchangeLock) {
            cancelled = true
            activeConnection
        }
        connection?.close()
    }

    internal fun endpointFor(work: NativeIssuerWork): URL {
        val base = work.issuerUrl.toURI()
        require(base.scheme.equals("https", ignoreCase = true) && base.host != null && base.userInfo == null) {
            "invalid issuer origin"
        }
        require(work.issuerCarrierPath.isNotEmpty() && work.issuerCarrierPath.length <= maximumIssuerPathCharacters) {
            "invalid issuer path"
        }
        require(work.issuerCarrierPath.startsWith("/") && !work.issuerCarrierPath.startsWith("//")) {
            "invalid issuer path"
        }
        require(!work.issuerCarrierPath.contains('?') && !work.issuerCarrierPath.contains('#') && !work.issuerCarrierPath.contains('\\')) {
            "invalid issuer path"
        }
        val endpoint = URI(base.scheme, null, base.host, base.port, work.issuerCarrierPath, null, null).toURL()
        require(endpoint.protocol.equals("https", ignoreCase = true) && endpoint.host.equals(base.host, ignoreCase = true)) {
            "issuer origin changed"
        }
        return endpoint
    }

    private fun readBounded(input: InputStream): ByteArray {
        var result = ByteArray(initialResponseBytes)
        var resultLength = 0
        val chunk = ByteArray(readChunkBytes)
        try {
            while (true) {
                val read = input.read(chunk)
                if (read < 0) {
                    break
                }
                if (read == 0) {
                    continue
                }
                require(read <= maximumIssuerResponseBytes - resultLength) { "issuer response exceeds size limit" }
                if (resultLength + read > result.size) {
                    val grownSize = minOf(maximumIssuerResponseBytes, maxOf(result.size * 2, resultLength + read))
                    val grown = ByteArray(grownSize)
                    result.copyInto(grown, endIndex = resultLength)
                    result.fill(0)
                    result = grown
                }
                chunk.copyInto(result, destinationOffset = resultLength, endIndex = read)
                resultLength += read
            }
            require(resultLength > 0) { "issuer response is empty" }
            return result.copyOf(resultLength)
        } finally {
            result.fill(0)
            chunk.fill(0)
        }
    }

    private fun isBinaryContentType(value: String?): Boolean {
        val mediaType = value?.substringBefore(';')?.trim()
        return mediaType.equals("application/octet-stream", ignoreCase = true)
    }

    private companion object {
        const val maximumIssuerRequestBytes = 8 * 1024
        const val maximumIssuerPathCharacters = 8 * 1024
        const val maximumIssuerResponseBytes = 1024 * 1024
        const val initialResponseBytes = 8 * 1024
        const val readChunkBytes = 8 * 1024
    }
}

private class HttpsIssuerHttpConnection(endpoint: URL) : IssuerHttpConnection {
    private val connection = (endpoint.openConnection() as? HttpsURLConnection)
        ?: throw IllegalArgumentException("issuer endpoint is not HTTPS")

    init {
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.allowUserInteraction = false
        connection.doInput = true
    }

    override fun post(requestBody: ByteArray): IssuerHttpResponse {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(requestBody.size)
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.setRequestProperty("Accept", "application/octet-stream")
        connection.setRequestProperty("Cache-Control", "no-store")
        connection.outputStream.use { output ->
            output.write(requestBody)
            output.flush()
        }
        val statusCode = connection.responseCode
        return IssuerHttpResponse(
            statusCode = statusCode,
            contentLength = connection.contentLengthLong,
            body = if (statusCode == HttpsURLConnection.HTTP_OK) connection.inputStream else null,
            contentType = connection.contentType,
        )
    }

    override fun close() {
        connection.disconnect()
    }
}
