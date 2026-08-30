package org.aurora.protocol.android

internal enum class VpnServiceCommand {
    CONNECT,
    DISCONNECT,
}

internal data class PendingVpnServiceCommand(
    val command: VpnServiceCommand,
    val afterStatusRevision: Long,
    val timeoutAtUptimeMillis: Long,
    val connectRequestId: Long?,
)

internal fun vpnServiceCommand(action: String?): VpnServiceCommand? = when (action) {
    connectVpnAction -> VpnServiceCommand.CONNECT
    disconnectVpnAction -> VpnServiceCommand.DISCONNECT
    else -> null
}

internal fun hasValidConnectRequestId(command: VpnServiceCommand, connectRequestId: Long?): Boolean =
    if (command == VpnServiceCommand.CONNECT) {
        connectRequestId != null && connectRequestId > 0
    } else {
        connectRequestId == null
    }
