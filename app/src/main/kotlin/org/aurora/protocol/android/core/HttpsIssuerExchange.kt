package org.aurora.protocol.android.core

import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal class HttpsIssuerExchange(
    private val connectionFactory: IssuerHttpConnectionFactory = IssuerHttpConnectionFactory { endpoint ->
        HttpsIssuerHttpConnection(endpoint)
    },
    internal val exchangeTimeoutNanos: Long = TimeUnit.SECONDS.toNanos(exchangeTimeoutSeconds),
    internal val monotonicNanos: () -> Long = System::nanoTime,
    private val deadlineScheduler: IssuerDeadlineScheduler = SystemIssuerDeadlineScheduler,
) : CancellableIssuerExchange {
    internal val exchangeLock = Any()
    internal var activeConnection: ActiveIssuerConnection? = null
        internal set
    internal var exchangeInProgress = false
        internal set
    internal var cancelled = false
        internal set

    init {
        require(exchangeTimeoutNanos > 0) { "invalid issuer exchange timeout" }
    }

    override fun exchange(work: NativeIssuerWork): ByteArray {
        require(work.handle > 0) { "invalid issuer handle" }
        require(work.requestBody.isNotEmpty() && work.requestBody.size <= maximumIssuerRequestBytes) {
            "invalid issuer request body"
        }
        val endpoint = issuerEndpointFor(work)
        val startedAtNanos = monotonicNanos()
        synchronized(exchangeLock) {
            check(!cancelled) { "issuer exchange was cancelled" }
            check(!exchangeInProgress) { "issuer exchange is already in progress" }
            exchangeInProgress = true
        }

        var connection: ActiveIssuerConnection? = null
        var deadlineTask: IssuerDeadlineTask? = null
        var responseBody: InputStream? = null
        var responseBytes: ByteArray? = null
        var primaryFailure: Throwable? = null
        try {
            val openedConnection = ActiveIssuerConnection(
                connectionFactory.open(endpoint),
                { activeConnection -> releaseConnectionAfterAsynchronousClose(activeConnection) },
            )
            connection = openedConnection
            val accepted = synchronized(exchangeLock) {
                if (cancelled) {
                    false
                } else {
                    activeConnection = openedConnection
                    true
                }
            }
            check(accepted) { "issuer exchange was cancelled" }
            val remainingNanos = remainingNanos(startedAtNanos)
            if (remainingNanos <= 0) {
                synchronized(exchangeLock) {
                    if (activeConnection === openedConnection) {
                        openedConnection.timedOut = true
                    }
                }
                throw IssuerExchangeTimeoutException()
            }
            deadlineTask = deadlineScheduler.schedule(remainingNanos) {
                expire(openedConnection)
            }

            val response = openedConnection.connection.post(work.requestBody)
            responseBody = response.body
            ensureBeforeDeadline(startedAtNanos)
            require(response.statusCode == HttpsURLConnection.HTTP_OK) { "issuer rejected request" }
            require(isBinaryContentType(response.contentType)) { "issuer response content type is invalid" }
            require(response.contentLength in -1..maximumIssuerResponseBytes.toLong()) {
                "issuer response exceeds size limit"
            }
            val body = responseBody ?: throw IllegalStateException("issuer response body is unavailable")
            val result = readBounded(body, startedAtNanos, response.contentLength)
            responseBytes = result
            finishExchange(openedConnection, startedAtNanos, null)?.let { throw it }
            return result
        } catch (error: Throwable) {
            val failure = finishExchange(connection, startedAtNanos, error) ?: error
            primaryFailure = failure
            throw failure
        } finally {
            var failure = primaryFailure
            try {
                deadlineTask?.cancel()
            } catch (error: Exception) {
                failure = combineFailures(failure, error)
            }
            try {
                responseBody?.close()
            } catch (error: Exception) {
                failure = combineFailures(failure, error)
            }
            connection?.closeOnce()?.let { error ->
                failure = combineFailures(failure, error)
            }
            releaseConnectionAfterCleanup(connection)
            if (primaryFailure != null) {
                responseBytes?.fill(0)
            } else {
                failure?.let { cleanupFailure ->
                    responseBytes?.fill(0)
                    throw cleanupFailure
                }
            }
        }
    }

    override fun cancel() {
        val connection = synchronized(exchangeLock) {
            cancelled = true
            activeConnection
        }
        connection?.closeOnce()?.let { throw it }
    }

    internal companion object {
        const val maximumIssuerRequestBytes = 8 * 1024
        const val maximumIssuerResponseBytes = 1024 * 1024
        const val initialResponseBytes = 8 * 1024
        const val readChunkBytes = 8 * 1024
        const val exchangeTimeoutSeconds = 45L
    }
}
