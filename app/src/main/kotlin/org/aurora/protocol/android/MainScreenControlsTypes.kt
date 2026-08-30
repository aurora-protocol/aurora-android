package org.aurora.protocol.android

internal data class MainScreenControls(
    val importInputEnabled: Boolean,
    val importEnabled: Boolean,
    val removeProvisioningEnabled: Boolean,
    val connectEnabled: Boolean,
    val disconnectEnabled: Boolean,
    val showProgress: Boolean,
)

internal enum class MainScreenActionCopy {
    IMPORT,
    IMPORTING,
    REMOVE,
    REMOVING,
    CONNECT,
    CONNECTING,
    WAITING_FOR_PERMISSION,
    RETRY,
    DISCONNECT,
    CANCEL,
    DISCONNECTING,
}

internal data class MainScreenActionCopies(
    val importAction: MainScreenActionCopy,
    val removeAction: MainScreenActionCopy,
    val connectAction: MainScreenActionCopy,
    val disconnectAction: MainScreenActionCopy,
)
