package org.aurora.protocol.android

import org.aurora.protocol.android.core.AuroraLog
import java.util.concurrent.atomic.AtomicBoolean

internal class VpnConnectionCommand(
    private val lifecycle: VpnServiceLifecycle,
    private val generation: Long,
    private val work: () -> Unit,
) : Runnable {
    private val claimed = AtomicBoolean()

    override fun run() {
        if (!claimed.compareAndSet(false, true) || !lifecycle.beginConnectionWork(generation)) {
            return
        }
        try {
            work()
        } finally {
            lifecycle.finishConnectionWork(generation)
        }
    }

    /** Marks work proven never to have started by rejection or ExecutorService.shutdownNow(). */
    fun discardIfQueued() {
        if (claimed.compareAndSet(false, true)) {
            lifecycle.discardConnectionWork(generation)
        }
    }
}

internal val vpnTunnelStatus = VpnTunnelStatus()

internal val vpnProcessLifecycle = VpnProcessLifecycle(
    onTeardownFailure = { error -> AuroraLog.debug("tunnel resource cleanup", error) },
    tunnelStatus = vpnTunnelStatus,
)
