package org.aurora.protocol.android

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import org.aurora.protocol.android.core.AuroraLog

internal fun AuroraActivity.connect() {
    if (!currentControls().connectEnabled) {
        return
    }
    if (!requestState.beginConnectionRequest()) {
        return
    }
    showLocalStatus(R.string.status_preparing_connection)
    refreshControls()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        try {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), AuroraActivity.requestNotifications)
        } catch (error: RuntimeException) {
            failConnectionRequest("notification permission request", error)
        }
        return
    }
    requestVpnPreparation()
}

internal fun AuroraActivity.requestVpnPreparation() {
    if (!requestState.connectRequested) {
        return
    }
    try {
        val preparation = VpnService.prepare(this)
        if (preparation == null) {
            if (requestState.consumeConnectionRequest()) {
                refreshControls()
                startConnection()
            }
        } else {
            showLocalStatus(R.string.status_waiting_for_permission)
            startActivityForResult(preparation, AuroraActivity.requestVpnPermission)
        }
    } catch (error: RuntimeException) {
        failConnectionRequest("VPN permission request", error)
    }
}

internal fun AuroraActivity.failConnectionRequest(operation: String, error: RuntimeException) {
    AuroraLog.debug(operation, error)
    requestState.cancelConnectionRequest()
    showLocalStatus(R.string.status_connection_failed)
    refreshControls()
}
