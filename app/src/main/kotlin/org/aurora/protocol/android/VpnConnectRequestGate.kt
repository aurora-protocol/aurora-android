package org.aurora.protocol.android

/** Issues and invalidates process-local Connect requests without retaining per-request state. */
internal class VpnConnectRequestGate {
    private val lock = Any()
    private var issuedThrough = 0L
    private var resolvedThrough = 0L

    fun issue(): Long = synchronized(lock) {
        ++issuedThrough
    }

    fun invalidate(requestId: Long) = synchronized(lock) {
        if (requestId <= issuedThrough) {
            resolvedThrough = maxOf(resolvedThrough, requestId)
        }
    }

    fun claim(requestId: Long?): Boolean = synchronized(lock) {
        if (requestId == null || requestId <= resolvedThrough || requestId > issuedThrough) {
            return@synchronized false
        }
        resolvedThrough = requestId
        true
    }
}

internal val vpnConnectRequestGate = VpnConnectRequestGate()
