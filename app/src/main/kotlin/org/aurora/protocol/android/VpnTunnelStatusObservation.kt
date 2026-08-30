package org.aurora.protocol.android

internal fun VpnTunnelStatus.observe(observer: (TunnelStatus) -> Unit): () -> Unit {
    return observeCurrent(observer).unsubscribe
}

internal fun VpnTunnelStatus.observeCurrent(observer: (TunnelStatus) -> Unit): TunnelStatusObservation {
    val observation = observeCurrentPublication { update -> observer(update.status) }
    return TunnelStatusObservation(observation.publication.status, observation.unsubscribe)
}

internal fun VpnTunnelStatus.observeCurrentPublication(
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
