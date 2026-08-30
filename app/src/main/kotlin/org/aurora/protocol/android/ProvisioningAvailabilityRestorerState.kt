package org.aurora.protocol.android

/** Records provisioning store mutations before publishing their ready states. */
internal fun ProvisioningAvailabilityRestorer.recordImportedReservation(expiryUnix: Long) {
    require(expiryUnix > 0) { "invalid reservation expiry" }
    synchronized(lock) {
        ++generation
        knownExpiryUnix = expiryUnix
        tunnelStatus.publish(TunnelStatus.IDLE)
    }
}

/** Clears retained expiry metadata before publishing successful removal. */
internal fun ProvisioningAvailabilityRestorer.recordProvisioningRemoved() {
    synchronized(lock) {
        ++generation
        knownExpiryUnix = null
        tunnelStatus.publish(TunnelStatus.PROVISIONING_REQUIRED)
    }
}

/** Expires only the same stored entry while it remains available for a retry. */
internal fun ProvisioningAvailabilityRestorer.expireKnownReservation(expiryUnix: Long) {
    synchronized(lock) {
        if (knownExpiryUnix != expiryUnix || currentUnixTime() < expiryUnix) {
            return
        }
        val current = tunnelStatus.publication
        if (current.status != TunnelStatus.IDLE && current.status != TunnelStatus.FAILED) {
            return
        }
        ++generation
        tunnelStatus.publishIfCurrent(current, TunnelStatus.PROVISIONING_EXPIRED)
    }
}
