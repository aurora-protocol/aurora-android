package org.aurora.protocol.android

import android.app.Activity
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager

@Suppress("DEPRECATION")
internal fun AuroraActivity.handleVpnPermissionResult(resultCode: Int) {
    if (!requestState.consumeConnectionRequest()) {
        return
    }
    refreshControls()
    if (resultCode == Activity.RESULT_OK) {
        startConnection()
    } else {
        showLocalStatus(R.string.status_vpn_permission_required)
    }
}

internal fun AuroraActivity.handleNotificationPermissionResult(
    permissions: Array<out String>,
    grantResults: IntArray,
) {
    if (!requestState.connectRequested) {
        return
    }
    if (permissions.size != 1 ||
        permissions[0] != Manifest.permission.POST_NOTIFICATIONS ||
        grantResults.size != permissions.size
    ) {
        requestState.cancelConnectionRequest()
        showLocalStatus(R.string.status_permission_request_failed)
        refreshControls()
        return
    }
    requestVpnPreparation()
}

internal fun AuroraActivity.handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode != AuroraActivity.requestVpnPermission) {
        return
    }
    handleVpnPermissionResult(resultCode)
}

internal fun AuroraActivity.handleRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
) {
    if (requestCode != AuroraActivity.requestNotifications) {
        return
    }
    handleNotificationPermissionResult(permissions, grantResults)
}
