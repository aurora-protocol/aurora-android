package org.aurora.protocol.android

import android.app.Application
import org.aurora.protocol.android.core.AndroidKeystoreReservationStore
import org.aurora.protocol.android.core.AuroraReservationRepository
import org.aurora.protocol.android.core.NativeProvisioningReservationClient
import org.aurora.protocol.android.core.NativeTrustConfigurator

class AuroraApplication : Application() {
    internal lateinit var reservations: AuroraReservationRepository
        private set

    override fun onCreate() {
        super.onCreate()
        NativeTrustConfigurator.configure(this)
        reservations = AuroraReservationRepository(
            client = NativeProvisioningReservationClient.production(),
            storage = AndroidKeystoreReservationStore(this),
        )
    }
}
