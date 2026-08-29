package org.aurora.protocol.android

import java.util.concurrent.Executor

/** Restores process UI truth from the encrypted reservation store without consuming it. */
internal class ProvisioningAvailabilityRestorer(
    private val tunnelStatus: VpnTunnelStatus,
    private val hasUsableStoredReservation: (Long) -> Boolean,
    private val currentUnixTime: () -> Long,
    private val executor: Executor,
    private val onFailure: (Throwable) -> Unit,
) {
    private val lock = Any()
    private var generation = 0L

    fun start() {
        val probe = synchronized(lock) {
            AvailabilityProbe(
                generation = ++generation,
                expectedStatus = tunnelStatus.publish(TunnelStatus.CHECKING_PROVISIONING),
            )
        }
        try {
            executor.execute {
                val available = try {
                    hasUsableStoredReservation(currentUnixTime())
                } catch (error: Exception) {
                    onFailure(error)
                    false
                }
                complete(probe, available)
            }
        } catch (error: RuntimeException) {
            onFailure(error)
            complete(probe, available = false)
        }
    }

    /** Prevents an older storage read from overwriting a newer import or removal operation. */
    fun invalidate() {
        synchronized(lock) {
            ++generation
        }
    }

    private fun complete(probe: AvailabilityProbe, available: Boolean) {
        synchronized(lock) {
            if (generation != probe.generation) {
                return
            }
            ++generation
            tunnelStatus.publishIfCurrent(
                probe.expectedStatus,
                if (available) TunnelStatus.IDLE else TunnelStatus.PROVISIONING_REQUIRED,
            )
        }
    }

    private data class AvailabilityProbe(
        val generation: Long,
        val expectedStatus: TunnelStatusPublication,
    )
}
