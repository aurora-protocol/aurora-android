package org.aurora.protocol.android.core

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class ActiveIssuerConnection(
    val connection: IssuerHttpConnection,
    private val onCloseCompleted: (ActiveIssuerConnection) -> Unit,
) {
    private val closeStarted = AtomicBoolean(false)
    private val completedClose = AtomicBoolean(false)

    @Volatile
    private var closeFailure: Throwable? = null

    @Volatile
    var timedOut: Boolean = false

    @Volatile
    var finished: Boolean = false

    @Volatile
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

internal object SystemIssuerDeadlineScheduler : IssuerDeadlineScheduler {
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
