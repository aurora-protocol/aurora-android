package org.aurora.protocol.android

internal class ActiveConnection(
    val leaseId: Long,
    val generation: Long,
    var serviceStartId: Int,
    var connectionWorkStarted: Boolean = false,
    var connectionWorkComplete: Boolean = false,
    var session: AutoCloseable? = null,
    var runtime: AutoCloseable? = null,
    var provisioningTerminalStatus: TunnelStatus? = null,
)

internal class ActiveTeardown(
    val id: Long,
    val leaseId: Long,
    val connectionGeneration: Long,
    var connectionWorkStarted: Boolean,
    var connectionWorkComplete: Boolean,
    var lifecycleComplete: Boolean,
    var terminalStatus: TunnelStatus,
    var resourceCleanupComplete: Boolean = false,
)

internal fun terminalTunnelStatus(failed: Boolean, provisioningTerminalStatus: TunnelStatus?): TunnelStatus = when {
    provisioningTerminalStatus == TunnelStatus.PROVISIONING_EXPIRED -> TunnelStatus.PROVISIONING_EXPIRED
    failed && provisioningTerminalStatus != null -> TunnelStatus.FAILED_REQUIRES_PROVISIONING
    failed -> TunnelStatus.FAILED
    provisioningTerminalStatus != null -> TunnelStatus.PROVISIONING_REQUIRED
    else -> TunnelStatus.IDLE
}

internal fun TunnelStatus.withProvisioningUnavailable(unavailableStatus: TunnelStatus): TunnelStatus = when {
    unavailableStatus == TunnelStatus.PROVISIONING_EXPIRED -> TunnelStatus.PROVISIONING_EXPIRED
    this == TunnelStatus.FAILED || this == TunnelStatus.FAILED_REQUIRES_PROVISIONING -> {
        TunnelStatus.FAILED_REQUIRES_PROVISIONING
    }
    else -> TunnelStatus.PROVISIONING_REQUIRED
}
