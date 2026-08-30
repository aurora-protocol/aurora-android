package org.aurora.protocol.android

import org.aurora.protocol.android.core.StoredReservationAvailability

internal fun ProvisioningAvailabilityRestorer.dispatch(probe: ProvisioningAvailabilityProbe) {
    try {
        executor.execute {
            val availability = try {
                storedReservationAvailability(currentUnixTime())
            } catch (error: Exception) {
                onFailure(error)
                StoredReservationAvailability.Missing
            }
            complete(probe, availability)
        }
    } catch (error: RuntimeException) {
        onFailure(error)
        complete(probe, StoredReservationAvailability.Missing)
    }
}

internal fun ProvisioningAvailabilityRestorer.complete(
    probe: ProvisioningAvailabilityProbe,
    availability: StoredReservationAvailability,
) {
    synchronized(lock) {
        if (generation != probe.generation) {
            return
        }
        ++generation
        knownExpiryUnix = (availability as? StoredReservationAvailability.Available)?.expiryUnix
        tunnelStatus.publishIfCurrent(
            probe.expectedStatus,
            when (availability) {
                is StoredReservationAvailability.Available -> probe.availableStatus
                StoredReservationAvailability.Missing -> TunnelStatus.PROVISIONING_REQUIRED
                StoredReservationAvailability.Expired -> TunnelStatus.PROVISIONING_EXPIRED
            },
        )
    }
}
