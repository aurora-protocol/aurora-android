package org.aurora.protocol.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AuroraActivity : Activity() {
    internal val worker: ExecutorService = Executors.newSingleThreadExecutor()
    internal lateinit var importField: EditText
    internal lateinit var importFieldError: TextView
    internal var defaultImportFieldTextColor = android.graphics.Color.BLACK
    internal lateinit var importButton: Button
    internal lateinit var removeProvisioningButton: Button
    internal lateinit var connectButton: Button
    internal lateinit var disconnectButton: Button
    internal lateinit var progressIndicator: ProgressBar
    internal lateinit var status: TextView
    internal lateinit var statusLabelText: CharSequence
    internal lateinit var requestState: ConnectionRequestState
    internal val importInputLock = Any()
    internal var pendingImport: CharArray? = null
    internal var statusObserver: (() -> Unit)? = null
    internal var storageOperationObserver: (() -> Unit)? = null
    internal var statusObserverGeneration = 0L
    internal var screenResumed = false
    internal var lastRenderedStorageOperation: ProvisioningStorageOperation? = null
    internal var removeProvisioningDialog: AlertDialog? = null
    internal lateinit var tunnelStatusRenderState: TunnelStatusRenderState
    internal lateinit var vpnServiceRequestTracker: VpnServiceRequestTracker
    internal val vpnServiceRequestHandler = Handler(Looper.getMainLooper())
    internal val reconcileVpnServiceRequest = Runnable { reconcilePendingVpnServiceRequest() }
    internal val provisioningExpiryMonitor = ProvisioningExpiryMonitor(
        currentTimeMillis = System::currentTimeMillis,
        schedule = { action, delayMillis -> vpnServiceRequestHandler.postDelayed(action, delayMillis) },
        cancel = vpnServiceRequestHandler::removeCallbacks,
        onExpired = { expiryUnix ->
            (application as AuroraApplication).provisioningAvailability.expireKnownReservation(expiryUnix)
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeMainScreen(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saveActivityState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        resumeMainScreenObservers()
    }

    override fun onPause() {
        pauseMainScreenObservers()
        super.onPause()
    }

    override fun onDestroy() {
        destroyMainScreenResources()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        handleActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        handleRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    internal companion object {
        const val requestNotifications = 1
        const val requestVpnPermission = 2
        const val savedConnectionRequest = "connection-requested"
        const val savedConnectionRequestProcessSession = "connection-request-process-session"
        const val savedVpnServiceCommand = "vpn-service-command"
        const val savedVpnServiceCommandRevision = "vpn-service-command-revision"
        const val savedVpnServiceCommandTimeout = "vpn-service-command-timeout"
        const val savedVpnServiceConnectRequestId = "vpn-service-connect-request-id"
        const val savedVpnServiceProcessSession = "vpn-service-process-session"
    }
}
