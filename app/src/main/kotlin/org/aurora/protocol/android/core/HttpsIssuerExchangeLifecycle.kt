package org.aurora.protocol.android.core

internal fun HttpsIssuerExchange.expire(connection: ActiveIssuerConnection) {
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

internal fun HttpsIssuerExchange.releaseConnectionAfterCleanup(connection: ActiveIssuerConnection?) {
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

internal fun HttpsIssuerExchange.releaseConnectionAfterAsynchronousClose(connection: ActiveIssuerConnection) {
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

internal fun HttpsIssuerExchange.finishExchange(
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

internal fun HttpsIssuerExchange.remainingNanos(startedAtNanos: Long): Long {
    val elapsed = monotonicNanos() - startedAtNanos
    if (elapsed < 0 || elapsed >= exchangeTimeoutNanos) {
        return 0
    }
    return exchangeTimeoutNanos - elapsed
}

internal fun HttpsIssuerExchange.ensureBeforeDeadline(startedAtNanos: Long) {
    if (remainingNanos(startedAtNanos) <= 0) {
        throw IssuerExchangeTimeoutException()
    }
}
