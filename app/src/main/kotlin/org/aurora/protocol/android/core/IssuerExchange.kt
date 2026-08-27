package org.aurora.protocol.android.core

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

internal fun interface IssuerDeadlineTask {
    fun cancel()
}

internal fun interface IssuerDeadlineScheduler {
    fun schedule(delayNanos: Long, action: () -> Unit): IssuerDeadlineTask
}

internal class IssuerExchangeTimeoutException : IOException("issuer exchange timed out")

internal class HttpsIssuerExchange(
    private val connectionFactory: IssuerHttpConnectionFactory = IssuerHttpConnectionFactory { endpoint ->
        HttpsIssuerHttpConnection(endpoint)
    },
    private val exchangeTimeoutNanos: Long = TimeUnit.SECONDS.toNanos(exchangeTimeoutSeconds),
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val deadlineScheduler: IssuerDeadlineScheduler = SystemIssuerDeadlineScheduler,
) : CancellableIssuerExchange {
    private val exchangeLock = Any()
    private var activeConnection: ActiveIssuerConnection? = null
    private var exchangeInProgress = false
    private var cancelled = false

    init {
        require(exchangeTimeoutNanos > 0) { "invalid issuer exchange timeout" }
    }

    override fun exchange(work: NativeIssuerWork): ByteArray {
        require(work.handle > 0) { "invalid issuer handle" }
        require(work.requestBody.isNotEmpty() && work.requestBody.size <= maximumIssuerRequestBytes) {
            "invalid issuer request body"
        }
        val endpoint = endpointFor(work)
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
                ::releaseConnectionAfterAsynchronousClose,
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
            require(response.contentLength in -1..maximumIssuerResponseBytes.toLong()) { "issuer response exceeds size limit" }
            val body = responseBody ?: throw IllegalStateException("issuer response body is unavailable")
            val result = readBounded(body, startedAtNanos)
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
            } catch (error: Throwable) {
                failure = combineFailures(failure, error)
            }
            try {
                responseBody?.close()
            } catch (error: Throwable) {
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

    private fun expire(connection: ActiveIssuerConnection) {
        val shouldClose = synchronized(exchangeLock) {
            if (activeConnection !== connection || !exchangeInProgress || cancelled || connection.finished) {
                false
            } else {
                connection.timedOut = true
                true
            }
        }
        if (shouldClose) {
            connection.closeOnce()
        }
    }

    private fun releaseConnectionAfterCleanup(connection: ActiveIssuerConnection?) {
        synchronized(exchangeLock) {
            if (connection == null) {
                exchangeInProgress = false
                return
            }
            if (activeConnection !== connection) {
                if (activeConnection == null) {
                    exchangeInProgress = false
                }
                return
            }
            if (connection.closeCompleted) {
                if (connection.closeFailed) {
                    cancelled = true
                }
                activeConnection = null
                exchangeInProgress = false
            } else {
                // A deadline task owns a close that already unblocked this call.
                // Keep the exchange poisoned until that close actually returns.
                connection.releaseWhenClosed = true
                if (connection.closeCompleted) {
                    if (connection.closeFailed) {
                        cancelled = true
                    }
                    activeConnection = null
                    exchangeInProgress = false
                }
            }
        }
    }

    private fun releaseConnectionAfterAsynchronousClose(connection: ActiveIssuerConnection) {
        synchronized(exchangeLock) {
            if (activeConnection === connection && connection.releaseWhenClosed) {
                if (connection.closeFailed) {
                    cancelled = true
                }
                activeConnection = null
                exchangeInProgress = false
            }
        }
    }

    private fun finishExchange(
        connection: ActiveIssuerConnection?,
        startedAtNanos: Long,
        failure: Throwable?,
    ): Throwable? {
        val terminalState = synchronized(exchangeLock) {
            val deadlineExpired = remainingNanos(startedAtNanos) <= 0
            if (connection != null && activeConnection === connection) {
                if (!connection.timedOut && deadlineExpired) {
                    connection.timedOut = true
                }
                // Keep ownership until final teardown so neither sequential reuse
                // nor cancel() can target a newer connection while this one closes.
                connection.finished = true
            }
            Pair(connection?.timedOut == true || (connection == null && deadlineExpired), cancelled)
        }
        val (timedOut, wasCancelled) = terminalState
        if (!timedOut) {
            if (wasCancelled && failure == null) {
                return IllegalStateException("issuer exchange was cancelled")
            }
            return failure
        }
        if (failure is IssuerExchangeTimeoutException) {
            return failure
        }
        val timeout = IssuerExchangeTimeoutException()
        if (failure != null && failure !== timeout) {
            timeout.addSuppressed(failure)
        }
        return timeout
    }

    private fun remainingNanos(startedAtNanos: Long): Long {
        val elapsed = monotonicNanos() - startedAtNanos
        if (elapsed < 0 || elapsed >= exchangeTimeoutNanos) {
            return 0
        }
        return exchangeTimeoutNanos - elapsed
    }

    private fun ensureBeforeDeadline(startedAtNanos: Long) {
        if (remainingNanos(startedAtNanos) <= 0) {
            throw IssuerExchangeTimeoutException()
        }
    }

    internal fun endpointFor(work: NativeIssuerWork): URL {
        require(work.issuerUrl.toExternalForm().utf8Size() in 1..maximumIssuerComponentBytes) {
            "invalid issuer origin"
        }
        val base = work.issuerUrl.toURI()
        require(base.scheme.equals("https", ignoreCase = true) && base.host != null && base.userInfo == null) {
            "invalid issuer origin"
        }
        require(base.rawAuthority != null && !(base.port == -1 && base.rawAuthority.endsWith(':'))) {
            "invalid issuer origin"
        }
        require(base.port == -1 || base.port in 1..65_535) { "invalid issuer origin" }
        require(base.rawPath.isNullOrEmpty()) { "invalid issuer origin" }
        require(base.rawQuery == null && base.rawFragment == null) { "invalid issuer origin" }
        require(work.issuerCarrierPath.utf8Size() in 2..maximumIssuerComponentBytes) {
            "invalid issuer path"
        }
        require(
            work.issuerCarrierPath.startsWith("/") &&
                !work.issuerCarrierPath.contains("//") &&
                !work.issuerCarrierPath.endsWith('/'),
        ) {
            "invalid issuer path"
        }
        require(!work.issuerCarrierPath.contains('?') && !work.issuerCarrierPath.contains('#') && !work.issuerCarrierPath.contains('\\')) {
            "invalid issuer path"
        }
        val carrierPath = URI(null, null, work.issuerCarrierPath, null)
        require(carrierPath.rawPath == work.issuerCarrierPath && carrierPath.normalize().rawPath == work.issuerCarrierPath) {
            "invalid issuer path"
        }
        val endpoint = URI(base.scheme, null, base.host, base.port, work.issuerCarrierPath, null, null).toURL()
        require(endpoint.protocol.equals("https", ignoreCase = true) && endpoint.host.equals(base.host, ignoreCase = true)) {
            "issuer origin changed"
        }
        return endpoint
    }

    private fun readBounded(input: InputStream, startedAtNanos: Long): ByteArray {
        var result = ByteArray(initialResponseBytes)
        var resultLength = 0
        val chunk = ByteArray(readChunkBytes)
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

    private fun combineFailures(first: Throwable?, next: Throwable): Throwable {
        if (first == null) {
            return next
        }
        if (first !== next) {
            first.addSuppressed(next)
        }
        return first
    }

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

    private companion object {
        const val maximumIssuerRequestBytes = 8 * 1024
        const val maximumIssuerComponentBytes = 2 * 1024
        const val maximumIssuerResponseBytes = 1024 * 1024
        const val initialResponseBytes = 8 * 1024
        const val readChunkBytes = 8 * 1024
        const val exchangeTimeoutSeconds = 45L
    }
}

private class ActiveIssuerConnection(
    val connection: IssuerHttpConnection,
    private val onCloseCompleted: (ActiveIssuerConnection) -> Unit,
) {
    private val closeStarted = AtomicBoolean(false)
    private val completedClose = AtomicBoolean(false)

    @Volatile
    private var closeFailure: Throwable? = null

    var timedOut: Boolean = false

    var finished: Boolean = false

    var releaseWhenClosed: Boolean = false

    val closeCompleted: Boolean
        get() = completedClose.get()

    val closeFailed: Boolean
        get() = closeFailure != null

    fun closeOnce(): Throwable? {
        if (closeStarted.compareAndSet(false, true)) {
            try {
                connection.close()
            } catch (error: Throwable) {
                closeFailure = error
            } finally {
                completedClose.set(true)
                onCloseCompleted(this)
            }
        }
        return closeFailure
    }
}

private object SystemIssuerDeadlineScheduler : IssuerDeadlineScheduler {
    override fun schedule(delayNanos: Long, action: () -> Unit): IssuerDeadlineTask {
        require(delayNanos > 0) { "invalid issuer deadline delay" }
        val executor = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, deadlineThreadName).apply {
                isDaemon = true
            }
        }.apply {
            removeOnCancelPolicy = true
            setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        }
        val future = try {
            executor.schedule(
                {
                    try {
                        action()
                    } finally {
                        executor.shutdown()
                    }
                },
                delayNanos,
                TimeUnit.NANOSECONDS,
            )
        } catch (error: Throwable) {
            executor.shutdownNow()
            throw error
        }
        return IssuerDeadlineTask {
            future.cancel(false)
            // Do not interrupt a deadline action that is currently closing the
            // connection. It owns teardown until close returns and then shuts
            // this one-shot executor down from its own finally block.
            executor.shutdown()
        }
    }

    private const val deadlineThreadName = "aurora-issuer-deadline"
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
