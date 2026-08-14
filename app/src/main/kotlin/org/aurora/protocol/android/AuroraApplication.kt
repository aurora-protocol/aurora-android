package org.aurora.protocol.android

import android.app.Application
import org.aurora.protocol.android.core.NativeTrustConfigurator

class AuroraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NativeTrustConfigurator.configure(this)
    }
}
