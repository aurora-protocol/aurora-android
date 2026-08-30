package org.aurora.protocol.android.core

import java.io.IOException
import java.io.InputStream
import java.net.URL

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

internal fun interface IssuerDeadlineTask {
    fun cancel()
}

internal fun interface IssuerDeadlineScheduler {
    fun schedule(delayNanos: Long, action: () -> Unit): IssuerDeadlineTask
}

internal class IssuerExchangeTimeoutException : IOException("issuer exchange timed out")
