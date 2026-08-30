package org.aurora.protocol.android

import org.aurora.protocol.android.core.AuroraLog

internal fun AuroraVpnService.startTunnel(serviceStartId: Int) {
    val ownGeneration = when (val start = lifecycle.start(serviceStartId)) {
        is VpnConnectionStart.Accepted -> start.generation
        VpnConnectionStart.Shared -> return
        VpnConnectionStart.Rejected -> {
            collectCleanupFailures({ stopSelfResult(serviceStartId) })?.let { error ->
                AuroraLog.debug("rejected tunnel start cleanup", error)
            }
            return
        }
    }
    val connectionCommand = VpnConnectionCommand(lifecycle, ownGeneration) {
        runConnection(ownGeneration)
    }
    try {
        enterVpnForeground()
        commandExecutor.execute(connectionCommand)
    } catch (error: RuntimeException) {
        connectionCommand.discardIfQueued()
        AuroraLog.debug("tunnel startup", error)
        stopTunnel(
            stopService = true,
            expectedGeneration = ownGeneration,
            failed = true,
        )
    }
}
