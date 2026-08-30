package org.aurora.protocol.android

internal sealed interface VpnConnectionStart {
    data class Accepted(val generation: Long) : VpnConnectionStart

    data object Shared : VpnConnectionStart

    data object Rejected : VpnConnectionStart
}

internal sealed interface VpnConnectionStop {
    data class Started(
        val teardownId: Long,
        val serviceStartId: Int?,
    ) : VpnConnectionStop

    data class AlreadyInProgress(val serviceStartId: Int?) : VpnConnectionStop

    data object Ignored : VpnConnectionStop
}
