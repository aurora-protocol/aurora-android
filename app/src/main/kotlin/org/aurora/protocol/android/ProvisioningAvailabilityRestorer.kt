package org.aurora.protocol.android

import java.util.concurrent.Executor
import org.aurora.protocol.android.core.StoredReservationAvailability

/** Restores process UI truth from the encrypted reservation store without consuming it. */
internal class ProvisioningAvailabilityRestorer(
    private val tunnelStatus: VpnTunnelStatus,
    private val storedReservationAvailability: (Long) -> StoredReservationAvailability,
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
                val availability = try {
                    storedReservationAvailability(currentUnixTime())
                } catch (error: Exception) {
                    onFailure(error)
                    StoredReservationAvailability.MISSING
                }
                complete(probe, availability)
            }
        } catch (error: RuntimeException) {
            onFailure(error)
            complete(probe, StoredReservationAvailability.MISSING)
        }
    }

    /** Prevents an older storage read from overwriting a newer import or removal operation. */
    fun invalidate() {
        synchronized(lock) {
            ++generation
        }
    }

    private fun complete(probe: AvailabilityProbe, availability: StoredReservationAvailability) {
        synchronized(lock) {
            if (generation != probe.generation) {
                return
            }
            ++generation
            tunnelStatus.publishIfCurrent(
                probe.expectedStatus,
                when (availability) {
                    StoredReservationAvailability.AVAILABLE -> TunnelStatus.IDLE
                    StoredReservationAvailability.MISSING -> TunnelStatus.PROVISIONING_REQUIRED
                    StoredReservationAvailability.EXPIRED -> TunnelStatus.PROVISIONING_EXPIRED
                },
            )
        }
    }

    private data class AvailabilityProbe(
        val generation: Long,
        val expectedStatus: TunnelStatusPublication,
    )
}
