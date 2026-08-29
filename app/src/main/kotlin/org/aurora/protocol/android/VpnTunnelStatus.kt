package org.aurora.protocol.android

/** Lifecycle classifications the service publishes and the UI may render. */
internal enum class TunnelStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    FAILED,
}

/**
 * Process-scoped tunnel status channel shared by the service and the activity,
 * which always run in one process. Observers receive publications on the
 * publishing thread; a throwing observer never breaks the channel.
 */
internal class VpnTunnelStatus {
    private val lock = Any()
    // Identity semantics: adapted callable references hash their bound receiver,
    // which may mutate between registration and removal.
    private val observers = mutableListOf<(TunnelStatus) -> Unit>()
    private var current = TunnelStatus.IDLE

    val status: TunnelStatus
        get() = synchronized(lock) { current }

    fun publish(update: TunnelStatus) {
        val targets = synchronized(lock) {
            current = update
            observers.toList()
        }
        targets.forEach { observer ->
            try {
                observer(update)
            } catch (_: Throwable) {
                // Status observation must never break lifecycle publication.
            }
        }
    }

    /** Registers [observer] for future publications; returns an unsubscribe action. */
    fun observe(observer: (TunnelStatus) -> Unit): () -> Unit {
        return observeCurrent(observer).unsubscribe
    }

    /**
     * Atomically registers [observer] and captures the current classification.
     * This closes the read-then-observe gap for screens that must catch every
     * transition after the snapshot they render.
     */
    fun observeCurrent(observer: (TunnelStatus) -> Unit): TunnelStatusObservation {
        val initial = synchronized(lock) {
            observers += observer
            current
        }
        return TunnelStatusObservation(initial) {
            synchronized(lock) {
                observers.removeIf { it === observer }
            }
        }
    }
}

internal data class TunnelStatusObservation(
    val status: TunnelStatus,
    val unsubscribe: () -> Unit,
)

internal fun tunnelStatusText(status: TunnelStatus): Int = when (status) {
    TunnelStatus.IDLE -> R.string.status_ready
    TunnelStatus.CONNECTING -> R.string.status_connecting
    TunnelStatus.CONNECTED -> R.string.status_connected
    TunnelStatus.DISCONNECTING -> R.string.status_disconnecting
    TunnelStatus.FAILED -> R.string.status_tunnel_failed
}
