package org.aurora.protocol.android

import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenControlsTest {
    @Test
    fun importRequiresNonEmptyInput() {
        assertEquals(
            MainScreenControls(
                importInputEnabled = true,
                importEnabled = false,
                removeProvisioningEnabled = true,
                connectEnabled = true,
                disconnectEnabled = false,
                showProgress = false,
            ),
            mainScreenControls(
                importInProgress = false,
                storageOperationInProgress = false,
                connectRequested = false,
                pendingVpnServiceCommand = null,
                hasProvisioningInput = false,
                tunnelStatus = TunnelStatus.IDLE,
            ),
        )
    }

    @Test
    fun idleScreenEnablesImportWhenInputIsPresent() {
        assertEquals(
            MainScreenControls(
                importInputEnabled = true,
                importEnabled = true,
                removeProvisioningEnabled = true,
                connectEnabled = true,
                disconnectEnabled = false,
                showProgress = false,
            ),
            mainScreenControls(
                importInProgress = false,
                storageOperationInProgress = false,
                connectRequested = false,
                pendingVpnServiceCommand = null,
                hasProvisioningInput = true,
                tunnelStatus = TunnelStatus.IDLE,
            ),
        )
    }

    @Test
    fun importAndConnectionRequestsExposeBusyControls() {
        val expected = MainScreenControls(
            importInputEnabled = false,
            importEnabled = false,
            removeProvisioningEnabled = false,
            connectEnabled = false,
            disconnectEnabled = false,
            showProgress = true,
        )

        assertEquals(expected, mainScreenControls(true, false, false, null, true, TunnelStatus.IDLE))
        assertEquals(
            expected.copy(disconnectEnabled = true),
            mainScreenControls(false, false, true, null, true, TunnelStatus.IDLE),
        )
    }

    @Test
    fun `dispatched connect stays busy until the service publishes status`() {
        assertEquals(
            MainScreenControls(
                importInputEnabled = false,
                importEnabled = false,
                removeProvisioningEnabled = false,
                connectEnabled = false,
                disconnectEnabled = true,
                showProgress = true,
            ),
            mainScreenControls(false, false, false, VpnServiceCommand.CONNECT, true, TunnelStatus.IDLE),
        )
    }

    @Test
    fun `dispatched disconnect blocks duplicate commands until status advances`() {
        assertEquals(
            MainScreenControls(
                importInputEnabled = false,
                importEnabled = false,
                removeProvisioningEnabled = false,
                connectEnabled = false,
                disconnectEnabled = false,
                showProgress = true,
            ),
            mainScreenControls(false, false, false, VpnServiceCommand.DISCONNECT, true, TunnelStatus.CONNECTED),
        )
    }

    @Test
    fun `active tunnel disables duplicate starts and enables disconnect`() {
        val expected = MainScreenControls(
            importInputEnabled = false,
            importEnabled = false,
            removeProvisioningEnabled = false,
            connectEnabled = false,
            disconnectEnabled = true,
            showProgress = false,
        )

        assertEquals(
            expected.copy(showProgress = true),
            mainScreenControls(false, false, false, null, true, TunnelStatus.CONNECTING),
        )
        assertEquals(
            expected,
            mainScreenControls(false, false, false, null, true, TunnelStatus.CONNECTED),
        )
    }

    @Test
    fun `provisioning availability check blocks commands and reports progress`() {
        assertEquals(
            MainScreenControls(
                importInputEnabled = false,
                importEnabled = false,
                removeProvisioningEnabled = false,
                connectEnabled = false,
                disconnectEnabled = false,
                showProgress = true,
            ),
            mainScreenControls(
                false,
                false,
                false,
                null,
                true,
                TunnelStatus.CHECKING_PROVISIONING,
            ),
        )
    }

    @Test
    fun `failed tunnel returns controls to a retryable idle shape`() {
        assertEquals(
            MainScreenControls(
                importInputEnabled = true,
                importEnabled = true,
                removeProvisioningEnabled = true,
                connectEnabled = true,
                disconnectEnabled = false,
                showProgress = false,
            ),
            mainScreenControls(false, false, false, null, true, TunnelStatus.FAILED),
        )
    }

    @Test
    fun `consumed one-shot provisioning blocks connect while allowing a fresh import`() {
        val expected = MainScreenControls(
            importInputEnabled = true,
            importEnabled = true,
            removeProvisioningEnabled = false,
            connectEnabled = false,
            disconnectEnabled = false,
            showProgress = false,
        )

        assertEquals(
            expected,
            mainScreenControls(
                false,
                false,
                false,
                null,
                true,
                TunnelStatus.PROVISIONING_REQUIRED,
            ),
        )
        assertEquals(
            expected,
            mainScreenControls(
                false,
                false,
                false,
                null,
                true,
                TunnelStatus.FAILED_REQUIRES_PROVISIONING,
            ),
        )
    }

    @Test
    fun `disconnecting tunnel stays busy without offering a duplicate stop`() {
        assertEquals(
            MainScreenControls(
                importInputEnabled = false,
                importEnabled = false,
                removeProvisioningEnabled = false,
                connectEnabled = false,
                disconnectEnabled = false,
                showProgress = true,
            ),
            mainScreenControls(false, false, false, null, true, TunnelStatus.DISCONNECTING),
        )
    }

    @Test
    fun `storage operation blocks every conflicting command`() {
        assertEquals(
            MainScreenControls(
                importInputEnabled = false,
                importEnabled = false,
                removeProvisioningEnabled = false,
                connectEnabled = false,
                disconnectEnabled = false,
                showProgress = true,
            ),
            mainScreenControls(false, true, false, null, true, TunnelStatus.IDLE),
        )
    }
}
