package org.aurora.protocol.android

import android.os.Bundle

internal fun AuroraActivity.saveActivityState(outState: Bundle) {
    if (requestState.connectRequested) {
        outState.putBoolean(AuroraActivity.savedConnectionRequest, true)
        outState.putString(AuroraActivity.savedConnectionRequestProcessSession, vpnServiceProcessSessionId)
    } else {
        outState.remove(AuroraActivity.savedConnectionRequest)
        outState.remove(AuroraActivity.savedConnectionRequestProcessSession)
    }
    val pendingCommand = vpnServiceRequestTracker.pending
    if (pendingCommand == null) {
        outState.remove(AuroraActivity.savedVpnServiceCommand)
        outState.remove(AuroraActivity.savedVpnServiceCommandRevision)
        outState.remove(AuroraActivity.savedVpnServiceCommandTimeout)
        outState.remove(AuroraActivity.savedVpnServiceConnectRequestId)
        outState.remove(AuroraActivity.savedVpnServiceProcessSession)
    } else {
        outState.putString(AuroraActivity.savedVpnServiceCommand, pendingCommand.command.name)
        outState.putLong(AuroraActivity.savedVpnServiceCommandRevision, pendingCommand.afterStatusRevision)
        outState.putLong(AuroraActivity.savedVpnServiceCommandTimeout, pendingCommand.timeoutAtUptimeMillis)
        val connectRequestId = pendingCommand.connectRequestId
        if (connectRequestId == null) {
            outState.remove(AuroraActivity.savedVpnServiceConnectRequestId)
        } else {
            outState.putLong(AuroraActivity.savedVpnServiceConnectRequestId, connectRequestId)
        }
        outState.putString(AuroraActivity.savedVpnServiceProcessSession, vpnServiceProcessSessionId)
    }
}
