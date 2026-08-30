package org.aurora.protocol.android.core

import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class HttpsIssuerHttpConnection(endpoint: URL) : IssuerHttpConnection {
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
            body = if (statusCode == HttpsURLConnection.HTTP_OK) connection.inputStream else connection.errorStream,
            contentType = connection.contentType,
        )
    }

    override fun close() {
        connection.disconnect()
    }
}
