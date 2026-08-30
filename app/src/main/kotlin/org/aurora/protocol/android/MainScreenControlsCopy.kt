package org.aurora.protocol.android

internal fun hasProvisioningInput(text: CharSequence): Boolean {
    for (index in 0 until text.length) {
        if (!text[index].isWhitespace()) {
            return true
        }
    }
    return false
}

internal fun storageIdleRestoresTunnelStatus(
    previousOperation: ProvisioningStorageOperation?,
): Boolean = previousOperation == null

internal fun shouldShowImportFieldError(
    statusMessageId: Int,
    invalidImportMessageId: Int,
    saveFailedMessageId: Int,
    failedMessageId: Int,
): Boolean = statusMessageId == invalidImportMessageId ||
    statusMessageId == saveFailedMessageId ||
    statusMessageId == failedMessageId

internal fun isDisconnectCancelingConnection(
    connectRequested: Boolean,
    pendingVpnServiceCommand: VpnServiceCommand?,
    tunnelStatus: TunnelStatus,
): Boolean = connectRequested ||
    pendingVpnServiceCommand == VpnServiceCommand.CONNECT ||
    tunnelStatus == TunnelStatus.CONNECTING

internal fun mainScreenActionCopies(
    importInProgress: Boolean,
    storageOperation: ProvisioningStorageOperation?,
    connectRequested: Boolean,
    pendingVpnServiceCommand: VpnServiceCommand?,
    tunnelStatus: TunnelStatus,
): MainScreenActionCopies = MainScreenActionCopies(
    importAction = if (importInProgress) MainScreenActionCopy.IMPORTING else MainScreenActionCopy.IMPORT,
    removeAction = if (storageOperation == ProvisioningStorageOperation.REMOVING) {
        MainScreenActionCopy.REMOVING
    } else {
        MainScreenActionCopy.REMOVE
    },
    connectAction = when {
        connectRequested -> MainScreenActionCopy.WAITING_FOR_PERMISSION
        pendingVpnServiceCommand == VpnServiceCommand.CONNECT -> MainScreenActionCopy.CONNECTING
        tunnelStatus == TunnelStatus.FAILED -> MainScreenActionCopy.RETRY
        else -> MainScreenActionCopy.CONNECT
    },
    disconnectAction = when {
        pendingVpnServiceCommand == VpnServiceCommand.DISCONNECT -> MainScreenActionCopy.DISCONNECTING
        isDisconnectCancelingConnection(
            connectRequested = connectRequested,
            pendingVpnServiceCommand = pendingVpnServiceCommand,
            tunnelStatus = tunnelStatus,
        ) -> MainScreenActionCopy.CANCEL
        else -> MainScreenActionCopy.DISCONNECT
    },
)

internal fun mainScreenActionCopyResource(copy: MainScreenActionCopy): Int = when (copy) {
    MainScreenActionCopy.IMPORT -> R.string.action_import
    MainScreenActionCopy.IMPORTING -> R.string.action_importing
    MainScreenActionCopy.REMOVE -> R.string.action_remove_provisioning
    MainScreenActionCopy.REMOVING -> R.string.action_removing_provisioning
    MainScreenActionCopy.CONNECT -> R.string.action_connect
    MainScreenActionCopy.CONNECTING -> R.string.action_connecting
    MainScreenActionCopy.WAITING_FOR_PERMISSION -> R.string.action_waiting_for_permission
    MainScreenActionCopy.RETRY -> R.string.action_retry
    MainScreenActionCopy.DISCONNECT -> R.string.action_disconnect
    MainScreenActionCopy.CANCEL -> R.string.action_cancel
    MainScreenActionCopy.DISCONNECTING -> R.string.action_disconnecting
}

internal fun mainScreenActionHintResource(copy: MainScreenActionCopy): Int = when (copy) {
    MainScreenActionCopy.IMPORT, MainScreenActionCopy.IMPORTING -> R.string.action_import_hint
    MainScreenActionCopy.REMOVE, MainScreenActionCopy.REMOVING -> R.string.action_remove_provisioning_hint
    MainScreenActionCopy.CONNECT -> R.string.action_connect_hint
    MainScreenActionCopy.CONNECTING -> R.string.action_connecting_hint
    MainScreenActionCopy.WAITING_FOR_PERMISSION -> R.string.action_waiting_for_permission_hint
    MainScreenActionCopy.RETRY -> R.string.action_retry_hint
    MainScreenActionCopy.DISCONNECT -> R.string.action_disconnect_hint
    MainScreenActionCopy.CANCEL -> R.string.action_cancel_hint
    MainScreenActionCopy.DISCONNECTING -> R.string.action_disconnecting_hint
}
