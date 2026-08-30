package org.aurora.protocol.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `expired stored provisioning blocks connect while allowing import and removal`() {
        assertEquals(
            MainScreenControls(
                importInputEnabled = true,
                importEnabled = true,
                removeProvisioningEnabled = true,
                connectEnabled = false,
                disconnectEnabled = false,
                showProgress = false,
            ),
            mainScreenControls(
                false,
                false,
                false,
                null,
                true,
                TunnelStatus.PROVISIONING_EXPIRED,
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

    @Test
    fun whitespaceOnlyInputDoesNotEnableImport() {
        assertFalse(hasProvisioningInput(""))
        assertFalse(hasProvisioningInput(" \n\t"))
        assertTrue(hasProvisioningInput("abc"))
        assertTrue(hasProvisioningInput(" a"))
    }

    @Test
    fun removeBusyLabelAppliesOnlyWhileRemoving() {
        val importing = mainScreenActionCopies(
            false,
            ProvisioningStorageOperation.IMPORTING,
            false,
            null,
            TunnelStatus.IDLE,
        )
        assertEquals(MainScreenActionCopy.REMOVE, importing.removeAction)
        assertEquals(MainScreenActionCopy.IMPORT, importing.importAction)

        val importingRequested = mainScreenActionCopies(
            true,
            ProvisioningStorageOperation.IMPORTING,
            false,
            null,
            TunnelStatus.IDLE,
        )
        assertEquals(MainScreenActionCopy.IMPORTING, importingRequested.importAction)
        assertEquals(MainScreenActionCopy.REMOVE, importingRequested.removeAction)

        val removing = mainScreenActionCopies(
            false,
            ProvisioningStorageOperation.REMOVING,
            false,
            null,
            TunnelStatus.IDLE,
        )
        assertEquals(MainScreenActionCopy.REMOVING, removing.removeAction)
    }

    @Test
    fun connectAndDisconnectActionCopiesFollowPendingCommands() {
        assertEquals(
            MainScreenActionCopy.CONNECTING,
            mainScreenActionCopies(false, null, false, VpnServiceCommand.CONNECT, TunnelStatus.IDLE).connectAction,
        )
        assertEquals(
            MainScreenActionCopy.WAITING_FOR_PERMISSION,
            mainScreenActionCopies(false, null, true, null, TunnelStatus.IDLE).connectAction,
        )
        assertEquals(
            MainScreenActionCopy.DISCONNECTING,
            mainScreenActionCopies(false, null, false, VpnServiceCommand.DISCONNECT, TunnelStatus.CONNECTED)
                .disconnectAction,
        )
    }

    @Test
    fun disconnectCopyCancelsInProgressConnectionInsteadOfClaimingAnActiveTunnel() {
        assertTrue(
            isDisconnectCancelingConnection(
                connectRequested = true,
                pendingVpnServiceCommand = null,
                tunnelStatus = TunnelStatus.IDLE,
            ),
        )
        assertTrue(
            isDisconnectCancelingConnection(
                connectRequested = false,
                pendingVpnServiceCommand = VpnServiceCommand.CONNECT,
                tunnelStatus = TunnelStatus.IDLE,
            ),
        )
        assertTrue(
            isDisconnectCancelingConnection(
                connectRequested = false,
                pendingVpnServiceCommand = null,
                tunnelStatus = TunnelStatus.CONNECTING,
            ),
        )
        assertFalse(
            isDisconnectCancelingConnection(
                connectRequested = false,
                pendingVpnServiceCommand = null,
                tunnelStatus = TunnelStatus.CONNECTED,
            ),
        )
        assertEquals(
            MainScreenActionCopy.CANCEL,
            mainScreenActionCopies(false, null, true, null, TunnelStatus.IDLE).disconnectAction,
        )
        assertEquals(
            MainScreenActionCopy.CANCEL,
            mainScreenActionCopies(false, null, false, VpnServiceCommand.CONNECT, TunnelStatus.IDLE)
                .disconnectAction,
        )
        assertEquals(
            MainScreenActionCopy.CANCEL,
            mainScreenActionCopies(false, null, false, null, TunnelStatus.CONNECTING).disconnectAction,
        )
        assertEquals(
            MainScreenActionCopy.DISCONNECT,
            mainScreenActionCopies(false, null, false, null, TunnelStatus.CONNECTED).disconnectAction,
        )
        assertEquals(R.string.action_cancel, mainScreenActionCopyResource(MainScreenActionCopy.CANCEL))
        assertEquals(R.string.action_cancel_hint, mainScreenActionHintResource(MainScreenActionCopy.CANCEL))
    }

    @Test
    fun failedTunnelUsesRetryConnectCopy() {
        assertEquals(
            MainScreenActionCopy.RETRY,
            mainScreenActionCopies(false, null, false, null, TunnelStatus.FAILED).connectAction,
        )
        assertEquals(
            MainScreenActionCopy.CONNECT,
            mainScreenActionCopies(false, null, false, null, TunnelStatus.IDLE).connectAction,
        )
        assertEquals(R.string.action_retry, mainScreenActionCopyResource(MainScreenActionCopy.RETRY))
        assertEquals(R.string.action_retry_hint, mainScreenActionHintResource(MainScreenActionCopy.RETRY))
        assertEquals(
            R.string.action_waiting_for_permission_hint,
            mainScreenActionHintResource(MainScreenActionCopy.WAITING_FOR_PERMISSION),
        )
        assertEquals(
            R.string.action_connecting_hint,
            mainScreenActionHintResource(MainScreenActionCopy.CONNECTING),
        )
        assertEquals(
            R.string.action_disconnect_hint,
            mainScreenActionHintResource(MainScreenActionCopy.DISCONNECT),
        )
    }

    @Test
    fun storageIdleDoesNotRestoreTunnelStatusUntilCompletionMessage() {
        assertTrue(storageIdleRestoresTunnelStatus(null))
        assertFalse(storageIdleRestoresTunnelStatus(ProvisioningStorageOperation.IMPORTING))
        assertFalse(storageIdleRestoresTunnelStatus(ProvisioningStorageOperation.REMOVING))
    }

    @Test
    fun importFieldErrorMatchesInvalidSaveAndGenericFailuresOnly() {
        assertTrue(
            shouldShowImportFieldError(
                10,
                invalidImportMessageId = 10,
                saveFailedMessageId = 20,
                failedMessageId = 30,
            ),
        )
        assertTrue(
            shouldShowImportFieldError(
                20,
                invalidImportMessageId = 10,
                saveFailedMessageId = 20,
                failedMessageId = 30,
            ),
        )
        assertTrue(
            shouldShowImportFieldError(
                30,
                invalidImportMessageId = 10,
                saveFailedMessageId = 20,
                failedMessageId = 30,
            ),
        )
        assertFalse(
            shouldShowImportFieldError(
                40,
                invalidImportMessageId = 10,
                saveFailedMessageId = 20,
                failedMessageId = 30,
            ),
        )
    }
}
