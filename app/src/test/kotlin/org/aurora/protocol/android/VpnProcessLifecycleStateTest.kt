package org.aurora.protocol.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VpnProcessLifecycleStateTest {
    @Test
    fun terminalStatusPrefersExpiredProvisioning() {
        assertEquals(
            TunnelStatus.PROVISIONING_EXPIRED,
            terminalTunnelStatus(
                failed = true,
                provisioningTerminalStatus = TunnelStatus.PROVISIONING_EXPIRED,
            ),
        )
    }

    @Test
    fun terminalStatusMapsFailedProvisioningToReimport() {
        assertEquals(
            TunnelStatus.FAILED_REQUIRES_PROVISIONING,
            terminalTunnelStatus(
                failed = true,
                provisioningTerminalStatus = TunnelStatus.PROVISIONING_REQUIRED,
            ),
        )
    }

    @Test
    fun terminalStatusMapsCleanStopToRequiredWhenProvisioningWasMarked() {
        assertEquals(
            TunnelStatus.PROVISIONING_REQUIRED,
            terminalTunnelStatus(
                failed = false,
                provisioningTerminalStatus = TunnelStatus.PROVISIONING_REQUIRED,
            ),
        )
    }

    @Test
    fun withProvisioningUnavailableEscalatesFailedToReimport() {
        assertEquals(
            TunnelStatus.FAILED_REQUIRES_PROVISIONING,
            TunnelStatus.FAILED.withProvisioningUnavailable(TunnelStatus.PROVISIONING_REQUIRED),
        )
        assertEquals(
            TunnelStatus.PROVISIONING_EXPIRED,
            TunnelStatus.FAILED.withProvisioningUnavailable(TunnelStatus.PROVISIONING_EXPIRED),
        )
    }

    @Test
    fun withProvisioningUnavailableLeavesIdleAsRequired() {
        assertEquals(
            TunnelStatus.PROVISIONING_REQUIRED,
            TunnelStatus.IDLE.withProvisioningUnavailable(TunnelStatus.PROVISIONING_REQUIRED),
        )
        assertFalse(
            TunnelStatus.CONNECTED.withProvisioningUnavailable(TunnelStatus.PROVISIONING_REQUIRED) ==
                TunnelStatus.FAILED_REQUIRES_PROVISIONING,
        )
    }
}
