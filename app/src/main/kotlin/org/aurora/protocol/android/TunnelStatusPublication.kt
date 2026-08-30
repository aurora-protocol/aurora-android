package org.aurora.protocol.android

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
