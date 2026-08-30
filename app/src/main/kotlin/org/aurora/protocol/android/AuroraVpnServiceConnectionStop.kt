package org.aurora.protocol.android

import android.app.Service
import org.aurora.protocol.android.core.AuroraLog

internal fun AuroraVpnService.stopTunnel(
    stopService: Boolean,
    expectedGeneration: Long? = null,
    serviceStartId: Int? = null,
    failed: Boolean = false,
) {
    when (val stop = lifecycle.stop(expectedGeneration, serviceStartId, failed)) {
        VpnConnectionStop.Ignored -> return
        is VpnConnectionStop.AlreadyInProgress -> {
            if (stopService && stop.serviceStartId != null) {
                collectCleanupFailures({ stopSelfResult(stop.serviceStartId) })?.let { error ->
                    AuroraLog.debug("tunnel service cleanup", error)
                }
            }
        }
        is VpnConnectionStop.Started -> {
            val failure = try {
                collectCleanupFailures(
                    { stopForeground(Service.STOP_FOREGROUND_REMOVE) },
                    {
                        if (stopService) {
                            if (stop.serviceStartId == null) {
                                stopSelf()
                            } else {
                                stopSelfResult(stop.serviceStartId)
                            }
                        }
                    },
                )
            } finally {
                lifecycle.finishLifecycleStop(stop.teardownId)
            }
            failure?.let { error -> AuroraLog.debug("tunnel service cleanup", error) }
        }
    }
}
