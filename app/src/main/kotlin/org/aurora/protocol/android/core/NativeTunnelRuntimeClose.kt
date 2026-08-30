package org.aurora.protocol.android.core

import java.util.concurrent.TimeUnit

internal fun NativeTunnelRuntime.transitionToClosed(): Boolean {
    val ownsClose = state.getAndSet(NativeTunnelRuntime.RuntimeState.CLOSED) != NativeTunnelRuntime.RuntimeState.CLOSED
    finishClose(ownsClose)
    return ownsClose
}

internal fun NativeTunnelRuntime.finishClose(ownsClose: Boolean) {
    var interruption: InterruptedException? = null
    if (ownsClose) {
        try {
            closeResources()
        } catch (error: Throwable) {
            closeFailure.set(error)
        } finally {
            closeCompletion.countDown()
        }
    } else {
        interruption = awaitCloseCompletion()
    }
    if (Thread.currentThread() !in runtimeWorkers && workers.isShutdown) {
        interruption = awaitWorkerTermination(interruption)
    }
    finishInterruptedClose(interruption)
    closeFailure.get()?.let { throw it }
}

private fun NativeTunnelRuntime.awaitCloseCompletion(): InterruptedException? {
    var interruption: InterruptedException? = null
    while (true) {
        try {
            closeCompletion.await()
            break
        } catch (error: InterruptedException) {
            val first = interruption
            if (first == null) {
                interruption = error
            } else if (first !== error) {
                first.addSuppressed(error)
            }
        }
    }
    return interruption
}

private fun NativeTunnelRuntime.awaitWorkerTermination(initialInterruption: InterruptedException?): InterruptedException? {
    var interruption = initialInterruption
    while (!workers.isTerminated) {
        try {
            workers.awaitTermination(1, TimeUnit.DAYS)
        } catch (error: InterruptedException) {
            val first = interruption
            if (first == null) {
                interruption = error
            } else if (first !== error) {
                first.addSuppressed(error)
            }
        }
    }
    return interruption
}

private fun NativeTunnelRuntime.finishInterruptedClose(interruption: InterruptedException?) {
    interruption?.let { error ->
        Thread.currentThread().interrupt()
        closeFailure.get()?.let { failure ->
            if (failure !== error) {
                failure.addSuppressed(error)
            }
            throw failure
        }
        throw error
    }
}

private fun NativeTunnelRuntime.closeResources() {
    var failure: Throwable? = null
    try {
        session.close()
    } catch (error: Throwable) {
        failure = error
    }
    try {
        device.close()
    } catch (error: Throwable) {
        failure = combineTunnelRuntimeFailures(failure, error)
    }
    try {
        workers.shutdownNow()
    } catch (error: Throwable) {
        failure = combineTunnelRuntimeFailures(failure, error)
    }
    failure?.let { throw it }
}

internal fun NativeTunnelRuntime.transitionToClosedPreserving(primaryFailure: Throwable): Boolean {
    val ownsClose = state.getAndSet(NativeTunnelRuntime.RuntimeState.CLOSED) != NativeTunnelRuntime.RuntimeState.CLOSED
    return try {
        finishClose(ownsClose)
        ownsClose
    } catch (closeFailure: Throwable) {
        if (closeFailure !== primaryFailure) {
            primaryFailure.addSuppressed(closeFailure)
        }
        ownsClose
    }
}

private fun combineTunnelRuntimeFailures(first: Throwable?, next: Throwable): Throwable {
    if (first == null) {
        return next
    }
    if (first !== next) {
        first.addSuppressed(next)
    }
    return first
}
