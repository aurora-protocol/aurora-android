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
    private val observers = mutableListOf<(TunnelStatusPublication) -> Unit>()
    private var current = TunnelStatus.IDLE
    private var revision = 0L

    val status: TunnelStatus
        get() = synchronized(lock) { current }

    val publication: TunnelStatusPublication
        get() = synchronized(lock) { TunnelStatusPublication(current, revision) }

    fun publish(update: TunnelStatus) {
        val (published, targets) = synchronized(lock) {
            current = update
            val next = TunnelStatusPublication(update, ++revision)
            next to observers.toList()
        }
        targets.forEach { observer ->
            try {
                observer(published)
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
        val observation = observeCurrentPublication { update -> observer(update.status) }
        return TunnelStatusObservation(observation.publication.status, observation.unsubscribe)
    }

    /** Atomically registers [observer] and captures the current revisioned publication. */
    fun observeCurrentPublication(
        observer: (TunnelStatusPublication) -> Unit,
    ): TunnelStatusPublicationObservation {
        val initial = synchronized(lock) {
            observers += observer
            TunnelStatusPublication(current, revision)
        }
        return TunnelStatusPublicationObservation(initial) {
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

internal data class TunnelStatusPublication(
    val status: TunnelStatus,
    val revision: Long,
)

internal data class TunnelStatusPublicationObservation(
    val publication: TunnelStatusPublication,
    val unsubscribe: () -> Unit,
)

/** UI-side ordering gate for revisioned status callbacks and newer local feedback. */
internal class TunnelStatusRenderState(initial: TunnelStatusPublication) {
    private var handledRevision = initial.revision

    fun markLocalFeedback(current: TunnelStatusPublication) {
        handledRevision = maxOf(handledRevision, current.revision)
    }

    fun consumeIfCurrent(
        candidate: TunnelStatusPublication,
        current: TunnelStatusPublication,
    ): Boolean {
        if (candidate != current || candidate.revision <= handledRevision) {
            return false
        }
        handledRevision = candidate.revision
        return true
    }
}

internal fun tunnelStatusText(status: TunnelStatus): Int = when (status) {
    TunnelStatus.IDLE -> R.string.status_ready
    TunnelStatus.CONNECTING -> R.string.status_connecting
    TunnelStatus.CONNECTED -> R.string.status_connected
    TunnelStatus.DISCONNECTING -> R.string.status_disconnecting
    TunnelStatus.FAILED -> R.string.status_tunnel_failed
}
