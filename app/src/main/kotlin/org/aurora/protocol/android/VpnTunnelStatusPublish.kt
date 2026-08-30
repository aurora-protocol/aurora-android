package org.aurora.protocol.android

internal fun VpnTunnelStatus.publish(update: TunnelStatus): TunnelStatusPublication {
    val (published, targets) = synchronized(lock) {
        current = update
        val next = TunnelStatusPublication(update, ++revision)
        next to observers.toList()
    }
    notifyTunnelStatusObservers(published, targets)
    return published
}

internal fun VpnTunnelStatus.publishIfCurrent(expected: TunnelStatusPublication, update: TunnelStatus): Boolean {
    return publishIfCurrentAndGet(expected, update) != null
}

internal fun VpnTunnelStatus.publishIfCurrentAndGet(
    expected: TunnelStatusPublication,
    update: TunnelStatus,
): TunnelStatusPublication? {
    val publication = synchronized(lock) {
        if (current != expected.status || revision != expected.revision) {
            null
        } else {
            current = update
            val next = TunnelStatusPublication(update, ++revision)
            next to observers.toList()
        }
    } ?: return null
    notifyTunnelStatusObservers(publication.first, publication.second)
    return publication.first
}

internal fun VpnTunnelStatus.publishCurrentIfUnchanged(expectedRevision: Long): Boolean {
    val publication = synchronized(lock) {
        if (revision != expectedRevision) {
            null
        } else {
            val next = TunnelStatusPublication(current, ++revision)
            next to observers.toList()
        }
    } ?: return false
    notifyTunnelStatusObservers(publication.first, publication.second)
    return true
}

internal fun VpnTunnelStatus.notifyTunnelStatusObservers(
    publication: TunnelStatusPublication,
    targets: List<(TunnelStatusPublication) -> Unit>,
) {
    targets.forEach { observer ->
        try {
            observer(publication)
        } catch (_: Throwable) {
            // Status observation must never break lifecycle publication.
        }
    }
}
