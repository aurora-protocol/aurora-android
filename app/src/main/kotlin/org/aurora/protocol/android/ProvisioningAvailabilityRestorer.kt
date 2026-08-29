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
    private var observedMainScreenResume = false

    fun start() {
        val probe = synchronized(lock) {
            val probeGeneration = ++generation
            AvailabilityProbe(
                generation = probeGeneration,
                expectedStatus = tunnelStatus.publish(TunnelStatus.CHECKING_PROVISIONING),
                availableStatus = TunnelStatus.IDLE,
            )
        }
        dispatch(probe)
    }

    /**
     * Rechecks reservation truth after the main screen returns from the background.
     * The first resume reuses the process-start probe, and only retryable states
     * known to retain a reservation may enter a later check.
     */
    fun onMainScreenResumed(refreshAllowed: Boolean) {
        val probe = synchronized(lock) {
            if (!observedMainScreenResume) {
                observedMainScreenResume = true
                return@synchronized null
            }
            if (!refreshAllowed) {
                return@synchronized null
            }
            val current = tunnelStatus.publication
            if (current.status != TunnelStatus.IDLE && current.status != TunnelStatus.FAILED) {
                return@synchronized null
            }
            val probeGeneration = ++generation
            val checking = tunnelStatus.publishIfCurrentAndGet(
                current,
                TunnelStatus.CHECKING_PROVISIONING,
            ) ?: return@synchronized null
            AvailabilityProbe(
                generation = probeGeneration,
                expectedStatus = checking,
                availableStatus = current.status,
            )
        } ?: return
        dispatch(probe)
    }

    private fun dispatch(probe: AvailabilityProbe) {
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
                    StoredReservationAvailability.AVAILABLE -> probe.availableStatus
                    StoredReservationAvailability.MISSING -> TunnelStatus.PROVISIONING_REQUIRED
                    StoredReservationAvailability.EXPIRED -> TunnelStatus.PROVISIONING_EXPIRED
                },
            )
        }
    }

    private data class AvailabilityProbe(
        val generation: Long,
        val expectedStatus: TunnelStatusPublication,
        val availableStatus: TunnelStatus,
    )
}

/** Prevents availability publications from acknowledging or racing locally owned work. */
internal fun provisioningRefreshAllowed(
    importInProgress: Boolean,
    storageOperationInProgress: Boolean,
    connectRequested: Boolean,
    pendingVpnServiceCommand: VpnServiceCommand?,
): Boolean = !importInProgress &&
    !storageOperationInProgress &&
    !connectRequested &&
    pendingVpnServiceCommand == null
