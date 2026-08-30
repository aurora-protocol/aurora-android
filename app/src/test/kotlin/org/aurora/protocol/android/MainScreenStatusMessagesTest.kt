package org.aurora.protocol.android

import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenStatusMessagesTest {
    @Test
    fun initialStatusPrefersConnectionAndStorageOperations() {
        assertEquals(
            R.string.status_preparing_connection,
            mainScreenInitialStatusMessage(
                connectRequested = true,
                pendingVpnServiceCommand = VpnServiceCommand.CONNECT,
                initialStorageOperation = ProvisioningStorageOperation.IMPORTING,
                initialTunnelStatus = TunnelStatus.CONNECTED,
            ),
        )
        assertEquals(
            R.string.status_connecting,
            mainScreenInitialStatusMessage(
                connectRequested = false,
                pendingVpnServiceCommand = VpnServiceCommand.CONNECT,
                initialStorageOperation = null,
                initialTunnelStatus = TunnelStatus.IDLE,
            ),
        )
        assertEquals(
            R.string.status_importing,
            mainScreenInitialStatusMessage(
                connectRequested = false,
                pendingVpnServiceCommand = null,
                initialStorageOperation = ProvisioningStorageOperation.IMPORTING,
                initialTunnelStatus = TunnelStatus.IDLE,
            ),
        )
        assertEquals(
            R.string.status_ready,
            mainScreenInitialStatusMessage(
                connectRequested = false,
                pendingVpnServiceCommand = null,
                initialStorageOperation = null,
                initialTunnelStatus = TunnelStatus.IDLE,
            ),
        )
    }

    @Test
    fun idleStatusRestoresTunnelClassificationWhenNoLocalWork() {
        assertEquals(
            R.string.status_disconnect_requested,
            mainScreenIdleStatusMessage(
                connectRequested = false,
                pendingVpnServiceCommand = VpnServiceCommand.DISCONNECT,
                tunnelStatus = TunnelStatus.CONNECTED,
            ),
        )
        assertEquals(
            R.string.status_connected,
            mainScreenIdleStatusMessage(
                connectRequested = false,
                pendingVpnServiceCommand = null,
                tunnelStatus = TunnelStatus.CONNECTED,
            ),
        )
    }
}
