package org.aurora.protocol.android

/** Prevents availability publications from acknowledging or racing locally owned work. */
internal fun provisioningRefreshAllowed(
    importInProgress: Boolean,
    storageOperationInProgress: Boolean,
    connectRequested: Boolean,
    pendingVpnServiceCommand: VpnServiceCommand?,
): Boolean = !importInProgress &&
    !storageOperationInProgress &&
    !connectRequested &&
    pendingVpnServiceCommand == null

internal data class ProvisioningAvailabilityProbe(
    val generation: Long,
    val expectedStatus: TunnelStatusPublication,
    val availableStatus: TunnelStatus,
)
