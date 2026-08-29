package org.aurora.protocol.android

/**
 * Main-screen wall-clock monitor for the non-sensitive expiry of the active
 * reservation. A bounded reschedule interval notices clock changes without a
 * broadcast, while the process restorer still validates the final transition.
 */
internal class ProvisioningExpiryMonitor(
    private val currentTimeMillis: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onExpired: (Long) -> Unit,
) {
    private var monitoredExpiryUnix: Long? = null
    private val reconcileExpiry = Runnable { reconcile() }

    fun update(expiryUnix: Long?) {
        require(expiryUnix == null || expiryUnix > 0) { "invalid reservation expiry" }
        cancel(reconcileExpiry)
        monitoredExpiryUnix = expiryUnix
        scheduleNext()
    }

    fun stop() {
        update(null)
    }

    private fun reconcile() {
        val expiryUnix = monitoredExpiryUnix ?: return
        if (millisecondsUntilExpiry(expiryUnix, currentTimeMillis()) == 0L) {
            monitoredExpiryUnix = null
            onExpired(expiryUnix)
        } else {
            scheduleNext()
        }
    }

    private fun scheduleNext() {
        val expiryUnix = monitoredExpiryUnix ?: return
        val remaining = millisecondsUntilExpiry(expiryUnix, currentTimeMillis())
        schedule(reconcileExpiry, minOf(remaining, wallClockRecheckMillis))
    }
}

internal fun millisecondsUntilExpiry(expiryUnix: Long, currentTimeMillis: Long): Long {
    require(expiryUnix > 0) { "invalid reservation expiry" }
    val nonNegativeCurrentTime = maxOf(0L, currentTimeMillis)
    if (expiryUnix > Long.MAX_VALUE / millisPerSecond) {
        return Long.MAX_VALUE
    }
    val expiryMillis = expiryUnix * millisPerSecond
    return if (nonNegativeCurrentTime >= expiryMillis) {
        0L
    } else {
        expiryMillis - nonNegativeCurrentTime
    }
}

private const val millisPerSecond = 1_000L
internal const val wallClockRecheckMillis = 60_000L
