package org.aurora.protocol.android

import java.util.concurrent.Executor
import org.aurora.protocol.android.core.StoredReservationAvailability

/** Restores process UI truth from the encrypted reservation store without consuming it. */
internal class ProvisioningAvailabilityRestorer(
    internal val tunnelStatus: VpnTunnelStatus,
    internal val storedReservationAvailability: (Long) -> StoredReservationAvailability,
    internal val currentUnixTime: () -> Long,
    internal val executor: Executor,
    internal val onFailure: (Throwable) -> Unit,
) {
    internal val lock = Any()
    internal var generation = 0L
    private var observedMainScreenResume = false
    internal var knownExpiryUnix: Long? = null

    val knownReservationExpiryUnix: Long?
        get() = synchronized(lock) { knownExpiryUnix }

    fun start() {
        val probe = synchronized(lock) {
            val probeGeneration = ++generation
            ProvisioningAvailabilityProbe(
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
            ProvisioningAvailabilityProbe(
                generation = probeGeneration,
                expectedStatus = checking,
                availableStatus = current.status,
            )
        } ?: return
        dispatch(probe)
    }

    /** Prevents an older storage read from overwriting a newer import or removal operation. */
    fun invalidate() {
        synchronized(lock) {
            ++generation
        }
    }
}
