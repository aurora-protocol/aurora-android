package org.aurora.protocol.android

/** Lifecycle classifications the service publishes and the UI may render. */
internal enum class TunnelStatus {
    IDLE,
    CHECKING_PROVISIONING,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    FAILED,
    PROVISIONING_REQUIRED,
    PROVISIONING_EXPIRED,
    FAILED_REQUIRES_PROVISIONING,
}

/**
 * Process-scoped tunnel status channel shared by the service and the activity,
 * which always run in one process. Observers receive publications on the
 * publishing thread; a throwing observer never breaks the channel.
 */
internal class VpnTunnelStatus {
    internal val lock = Any()
    internal val observers = mutableListOf<(TunnelStatusPublication) -> Unit>()
    internal var current = TunnelStatus.IDLE
    internal var revision = 0L

    val status: TunnelStatus
        get() = synchronized(lock) { current }

    val publication: TunnelStatusPublication
        get() = synchronized(lock) { TunnelStatusPublication(current, revision) }
}
