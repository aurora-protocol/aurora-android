package org.aurora.protocol.android

import org.aurora.protocol.android.core.AuroraLog
import org.aurora.protocol.android.core.NativeSessionController
import org.aurora.protocol.android.core.NativeTunnelRuntime
import org.aurora.protocol.android.core.ReservationConsumption

internal fun AuroraVpnService.runConnection(ownGeneration: Long) {
    var device: FileDescriptorTunnelDevice? = null
    var candidateRuntime: NativeTunnelRuntime? = null
    var session: NativeSessionController? = null
    var sessionOwner: CloseOnceNativePacketSession? = null
    try {
        val createdSession = NativeSessionController()
        session = createdSession
        val ownedSession = CloseOnceNativePacketSession(createdSession)
        sessionOwner = ownedSession
        if (!lifecycle.attachSession(ownGeneration, ownedSession)) {
            collectCleanupFailures(ownedSession::close)?.let { error ->
                AuroraLog.debug("cancelled tunnel cleanup", error)
            }
            return
        }

        val consumption = (application as AuroraApplication).reservations.consume(
            System.currentTimeMillis() / 1_000,
        )
        val availabilityMarked = when (consumption) {
            is ReservationConsumption.Available -> lifecycle.markProvisioningRequired(ownGeneration)
            ReservationConsumption.Missing -> lifecycle.markProvisioningRequired(ownGeneration)
            ReservationConsumption.Expired -> lifecycle.markProvisioningExpired(ownGeneration)
        }
        if (!availabilityMarked) {
            (consumption as? ReservationConsumption.Available)?.reservation?.close()
            return
        }
        val activeReservation = (consumption as? ReservationConsumption.Available)?.reservation
            ?: throw UnavailableProvisioningException()
        try {
            createdSession.establish(activeReservation.provisioning) {
                device = establishTunnel()
            }
        } finally {
            activeReservation.close()
        }
        val establishedDevice = device ?: throw IllegalStateException("VPN interface is unavailable")
        val establishedRuntime = NativeTunnelRuntime(ownedSession, establishedDevice) { error ->
            AuroraLog.debug("tunnel runtime", error)
            stopTunnel(
                stopService = true,
                expectedGeneration = ownGeneration,
                failed = true,
            )
        }
        candidateRuntime = establishedRuntime
        if (!lifecycle.promoteRuntime(ownGeneration, ownedSession, establishedRuntime)) {
            collectCleanupFailures(establishedRuntime::close)?.let { error ->
                AuroraLog.debug("cancelled tunnel cleanup", error)
            }
            return
        }
        establishedRuntime.start()
    } catch (error: Throwable) {
        if (candidateRuntime == null) {
            collectCleanupFailures(
                { device?.close() },
                { sessionOwner?.close() ?: session?.close() },
            )?.let { cleanupFailure ->
                if (cleanupFailure !== error) {
                    error.addSuppressed(cleanupFailure)
                }
            }
        }
        AuroraLog.debug("tunnel establishment", error)
        stopTunnel(
            stopService = true,
            expectedGeneration = ownGeneration,
            failed = error !is UnavailableProvisioningException,
        )
    }
}
