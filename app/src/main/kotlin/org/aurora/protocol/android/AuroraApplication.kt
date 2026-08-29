package org.aurora.protocol.android

import android.app.Application
import java.util.concurrent.Executor
import org.aurora.protocol.android.core.AndroidKeystoreReservationStore
import org.aurora.protocol.android.core.AuroraLog
import org.aurora.protocol.android.core.AuroraReservationRepository
import org.aurora.protocol.android.core.NativeProvisioningReservationClient
import org.aurora.protocol.android.core.NativeTrustConfigurator

class AuroraApplication : Application() {
    internal lateinit var reservations: AuroraReservationRepository
        private set
    internal lateinit var provisioningAvailability: ProvisioningAvailabilityRestorer
        private set

    override fun onCreate() {
        super.onCreate()
        NativeTrustConfigurator.configure(this)
        reservations = AuroraReservationRepository(
            client = NativeProvisioningReservationClient.production(),
            storage = AndroidKeystoreReservationStore(this),
        )
        provisioningAvailability = ProvisioningAvailabilityRestorer(
            tunnelStatus = vpnTunnelStatus,
            hasUsableStoredReservation = reservations::hasUsableStoredReservation,
            currentUnixTime = { System.currentTimeMillis() / 1_000 },
            executor = provisioningAvailabilityExecutor,
            onFailure = { error -> AuroraLog.debug("provisioning availability", error) },
        )
        provisioningAvailability.start()
    }

    private companion object {
        val provisioningAvailabilityExecutor = Executor { runnable ->
            Thread(runnable, "aurora-provisioning-availability").start()
        }
    }
}
