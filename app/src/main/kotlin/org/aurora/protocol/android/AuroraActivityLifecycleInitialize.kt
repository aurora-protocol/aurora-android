package org.aurora.protocol.android

import android.os.Bundle
import android.view.WindowManager

internal fun AuroraActivity.initializeMainScreen(savedInstanceState: Bundle?) {
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    val restored = restoreMainScreenLifecycle(savedInstanceState)
    requestState = restored.requestState
    vpnServiceRequestTracker = restored.vpnServiceRequestTracker
    lastRenderedStorageOperation = restored.initialStorageOperation.operation
    tunnelStatusRenderState = TunnelStatusRenderState(restored.initialTunnelStatus)
    val initialStatusMessageId = mainScreenInitialStatusMessage(
        connectRequested = requestState.connectRequested,
        pendingVpnServiceCommand = vpnServiceRequestTracker.pending?.command,
        initialStorageOperation = restored.initialStorageOperation.operation,
        initialTunnelStatus = restored.initialTunnelStatus.status,
    )
    val (root, views) = buildMainScreenContent(
        initialStatusMessageId = initialStatusMessageId,
        onImportTextChanged = {
            clearImportFieldError()
            refreshControls()
        },
        onImport = ::importProvisioning,
        onRemoveProvisioning = ::confirmRemoveProvisioning,
        onConnect = ::connect,
        onDisconnect = ::disconnect,
    )
    importField = views.importField
    importFieldError = views.importFieldError
    defaultImportFieldTextColor = views.defaultImportFieldTextColor
    importButton = views.importButton
    removeProvisioningButton = views.removeProvisioningButton
    connectButton = views.connectButton
    disconnectButton = views.disconnectButton
    progressIndicator = views.progressIndicator
    status = views.status
    statusLabelText = views.statusLabelText
    setContentView(root)
    refreshControls()
    schedulePendingVpnServiceReconciliation()
}
