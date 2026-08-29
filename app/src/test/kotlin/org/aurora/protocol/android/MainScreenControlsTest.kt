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

        assertEquals(expected, mainScreenControls(true, false, false, true, TunnelStatus.IDLE))
        assertEquals(
            expected.copy(disconnectEnabled = true),
            mainScreenControls(false, false, true, true, TunnelStatus.IDLE),
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
            mainScreenControls(false, false, false, true, TunnelStatus.CONNECTING),
        )
        assertEquals(
            expected,
            mainScreenControls(false, false, false, true, TunnelStatus.CONNECTED),
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
            mainScreenControls(false, false, false, true, TunnelStatus.FAILED),
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
            mainScreenControls(false, false, false, true, TunnelStatus.DISCONNECTING),
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
            mainScreenControls(false, true, false, true, TunnelStatus.IDLE),
        )
    }
}
