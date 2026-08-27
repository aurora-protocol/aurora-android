package org.aurora.protocol.android

internal const val connectVpnAction = "org.aurora.protocol.android.action.CONNECT"
internal const val disconnectVpnAction = "org.aurora.protocol.android.action.DISCONNECT"

internal enum class VpnServiceCommand {
    CONNECT,
    DISCONNECT,
}

internal fun vpnServiceCommand(action: String?): VpnServiceCommand? = when (action) {
    connectVpnAction -> VpnServiceCommand.CONNECT
    disconnectVpnAction -> VpnServiceCommand.DISCONNECT
    else -> null
}

internal fun runVpnServiceRequest(request: () -> Unit): RuntimeException? = try {
    request()
    null
} catch (error: RuntimeException) {
    error
}

internal fun collectCleanupFailures(vararg steps: () -> Unit): Throwable? {
    var failure: Throwable? = null
    steps.forEach { step ->
        try {
            step()
        } catch (error: Throwable) {
            val first = failure
            if (first == null) {
                failure = error
            } else if (first !== error) {
                first.addSuppressed(error)
            }
        }
    }
    return failure
}
