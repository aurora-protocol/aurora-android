package org.aurora.protocol.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import org.aurora.protocol.android.core.AuroraLog
import org.aurora.protocol.android.core.ProvisioningImport

class AuroraActivity : Activity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var importField: EditText
    private lateinit var importButton: Button
    private lateinit var removeProvisioningButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var progressIndicator: ProgressBar
    private lateinit var status: TextView
    private lateinit var requestState: ConnectionRequestState
    private val importInputLock = Any()
    private var pendingImport: CharArray? = null
    private var storageOperationInProgress = false
    private var statusObserver: (() -> Unit)? = null
    private var renderedTunnelStatus: TunnelStatus? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        requestState = ConnectionRequestState(savedInstanceState?.getBoolean(savedConnectionRequest) == true)
        val initialTunnelStatus = vpnTunnelStatus.status
        renderedTunnelStatus = initialTunnelStatus
        setContentView(buildContent(initialTunnelStatus))
        refreshControls()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(savedConnectionRequest, requestState.connectRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        val observation = vpnTunnelStatus.observeCurrent { update ->
            runOnUiThread { renderTunnelStatus(update) }
        }
        // Catch up only when the classification changed since the last tunnel
        // publication this screen rendered, so local request/import feedback
        // shown while the tunnel stayed idle is not overwritten.
        val current = observation.status
        val rendered = renderedTunnelStatus
        if (rendered != null && current != rendered) {
            renderTunnelStatus(current)
        }
        statusObserver = observation.unsubscribe
    }

    override fun onPause() {
        statusObserver?.invoke()
        statusObserver = null
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        synchronized(importInputLock) {
            pendingImport?.fill('\u0000')
            pendingImport = null
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestVpnPermission || !requestState.consumeConnectionRequest()) {
            return
        }
        refreshControls()
        if (resultCode == RESULT_OK) {
            startConnection()
        } else {
            status.setText(R.string.status_vpn_permission_required)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != requestNotifications || !requestState.connectRequested) {
            return
        }
        if (permissions.size != 1 ||
            permissions[0] != Manifest.permission.POST_NOTIFICATIONS ||
            grantResults.size != permissions.size
        ) {
            requestState.cancelConnectionRequest()
            status.setText(R.string.status_permission_request_failed)
            refreshControls()
            return
        }
        // Notification permission is optional for foreground-service startup,
        // so an explicit denial must not prevent the requested VPN connection.
        requestVpnPreparation()
    }

    private fun buildContent(initialTunnelStatus: TunnelStatus): View {
        val contentPadding = dp(24)
        val itemSpacing = dp(12)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        importField = EditText(this).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO
            }
            filters = arrayOf(InputFilter.LengthFilter(ProvisioningImport.maximumEncodedCharacters))
            setHint(R.string.provisioning_import_hint)
            setSingleLine(false)
            minLines = 4
        }
        val importLabel = TextView(this).apply {
            setText(R.string.provisioning_import_label)
            labelFor = importField.id
        }
        status = TextView(this).apply {
            id = View.generateViewId()
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setText(
                if (requestState.connectRequested) {
                    R.string.status_waiting_for_permission
                } else {
                    tunnelStatusText(initialTunnelStatus)
                },
            )
        }
        val statusLabel = TextView(this).apply {
            setText(R.string.status_label)
            labelFor = status.id
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                isAccessibilityHeading = true
            }
        }
        progressIndicator = ProgressBar(this).apply {
            isIndeterminate = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }

        layout.addView(importLabel, matchWidth())
        layout.addView(importField, matchWidth(dp(4)))
        importButton = commandButton(R.string.action_import) { importProvisioning() }
        removeProvisioningButton = commandButton(R.string.action_remove_provisioning) {
            confirmRemoveProvisioning()
        }
        connectButton = commandButton(R.string.action_connect) { connect() }
        layout.addView(importButton, matchWidth(itemSpacing))
        layout.addView(removeProvisioningButton, matchWidth(itemSpacing))
        layout.addView(connectButton, matchWidth(itemSpacing))
        disconnectButton = commandButton(R.string.action_disconnect) { disconnect() }
        layout.addView(disconnectButton, matchWidth(itemSpacing))
        layout.addView(progressIndicator, wrapContent(itemSpacing))
        layout.addView(statusLabel, matchWidth(itemSpacing))
        layout.addView(status, matchWidth(dp(4)))

        importField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(text: Editable?) {
                refreshControls()
            }
        })

        return ScrollView(this).apply {
            isFillViewport = true
            addView(layout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            applySystemWindowInsets(this, contentPadding)
        }
    }

    private fun commandButton(label: Int, action: () -> Unit): Button = Button(this).apply {
        setText(label)
        setSingleLine(false)
        setOnClickListener { action() }
    }

    private fun importProvisioning() {
        if (!requestState.beginImport()) {
            return
        }
        val editable = importField.text
        if (!ProvisioningImport.hasValidEncodedLength(editable.length)) {
            editable.clear()
            requestState.completeImport()
            status.setText(R.string.status_import_failed)
            refreshControls()
            return
        }
        val encoded = CharArray(editable.length) { editable[it] }
        editable.clear()
        synchronized(importInputLock) {
            pendingImport = encoded
        }
        status.setText(R.string.status_importing)
        refreshControls()
        try {
            worker.execute {
                val message = try {
                    val ownsInput = synchronized(importInputLock) {
                        if (pendingImport !== encoded) {
                            false
                        } else {
                            pendingImport = null
                            true
                        }
                    }
                    if (!ownsInput || Thread.currentThread().isInterrupted) {
                        encoded.fill('\u0000')
                        return@execute
                    }
                    val request = ProvisioningImport.decode(encoded)
                    try {
                        if (Thread.currentThread().isInterrupted) {
                            return@execute
                        }
                        (application as AuroraApplication).reservations.reserveAndPersist(
                            request,
                            System.currentTimeMillis() / 1_000,
                        )
                    } finally {
                        // The repository owns and clears successful calls. Keep a
                        // final caller-side scrub for initialization/cast failures.
                        request.fill(0)
                    }
                    R.string.status_import_succeeded
                } catch (error: Exception) {
                    AuroraLog.debug("provisioning import", error)
                    R.string.status_import_failed
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    requestState.completeImport()
                    status.setText(message)
                    refreshControls()
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(importInputLock) {
                if (pendingImport === encoded) {
                    pendingImport = null
                }
                encoded.fill('\u0000')
            }
            requestState.completeImport()
            status.setText(R.string.status_import_failed)
            refreshControls()
        }
    }

    private fun connect() {
        if (!requestState.beginConnectionRequest()) {
            return
        }
        status.setText(R.string.status_waiting_for_permission)
        refreshControls()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            try {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestNotifications)
            } catch (error: RuntimeException) {
                failConnectionRequest("notification permission request", error)
            }
            return
        }
        requestVpnPreparation()
    }

    private fun confirmRemoveProvisioning() {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_provisioning_title)
            .setMessage(R.string.remove_provisioning_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_remove_provisioning) { _, _ -> removeProvisioning() }
            .show()
    }

    private fun removeProvisioning() {
        val controls = currentControls()
        if (!controls.removeProvisioningEnabled) {
            return
        }
        storageOperationInProgress = true
        status.setText(R.string.status_removing_provisioning)
        refreshControls()
        try {
            worker.execute {
                val message = try {
                    (application as AuroraApplication).reservations.clear()
                    R.string.status_provisioning_removed
                } catch (error: Exception) {
                    AuroraLog.debug("provisioning removal", error)
                    R.string.status_remove_provisioning_failed
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    storageOperationInProgress = false
                    status.setText(message)
                    refreshControls()
                }
            }
        } catch (error: RejectedExecutionException) {
            AuroraLog.debug("provisioning removal dispatch", error)
            storageOperationInProgress = false
            status.setText(R.string.status_remove_provisioning_failed)
            refreshControls()
        }
    }

    private fun requestVpnPreparation() {
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
                status.setText(R.string.status_waiting_for_permission)
                startActivityForResult(preparation, requestVpnPermission)
            }
        } catch (error: RuntimeException) {
            failConnectionRequest("VPN permission request", error)
        }
    }

    private fun startConnection() {
        val failure = runVpnServiceRequest { AuroraVpnService.connect(this) }
        if (failure == null) {
            status.setText(R.string.status_connecting)
        } else {
            AuroraLog.debug("VPN service start", failure)
            status.setText(R.string.status_connection_failed)
        }
    }

    private fun disconnect() {
        requestState.cancelConnectionRequest()
        refreshControls()
        val failure = runVpnServiceRequest { AuroraVpnService.disconnect(this) }
        if (failure == null) {
            status.setText(R.string.status_disconnect_requested)
        } else {
            AuroraLog.debug("VPN service stop", failure)
            status.setText(R.string.status_disconnect_failed)
        }
    }

    private fun failConnectionRequest(operation: String, error: RuntimeException) {
        AuroraLog.debug(operation, error)
        requestState.cancelConnectionRequest()
        status.setText(R.string.status_connection_failed)
        refreshControls()
    }

    private fun renderTunnelStatus(update: TunnelStatus) {
        if (update != vpnTunnelStatus.status) {
            return
        }
        renderedTunnelStatus = update
        status.setText(tunnelStatusText(update))
        refreshControls()
    }

    private fun refreshControls() {
        val controls = currentControls()
        importField.isEnabled = controls.importInputEnabled
        importButton.isEnabled = controls.importEnabled
        importButton.setText(if (requestState.importInProgress) R.string.action_importing else R.string.action_import)
        removeProvisioningButton.isEnabled = controls.removeProvisioningEnabled
        removeProvisioningButton.setText(
            if (storageOperationInProgress) R.string.action_removing_provisioning else R.string.action_remove_provisioning,
        )
        connectButton.isEnabled = controls.connectEnabled
        connectButton.setText(if (requestState.connectRequested) R.string.action_waiting_for_permission else R.string.action_connect)
        disconnectButton.isEnabled = controls.disconnectEnabled
        progressIndicator.visibility = if (controls.showProgress) View.VISIBLE else View.GONE
    }

    private fun currentControls(): MainScreenControls = mainScreenControls(
        importInProgress = requestState.importInProgress,
        storageOperationInProgress = storageOperationInProgress,
        connectRequested = requestState.connectRequested,
        hasProvisioningInput = importField.text.isNotEmpty(),
        tunnelStatus = vpnTunnelStatus.status,
    )

    private fun matchWidth(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.topMargin = topMargin
        }

    private fun wrapContent(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.topMargin = topMargin
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun applySystemWindowInsets(view: View, contentPadding: Int) {
        view.setPadding(contentPadding, contentPadding, contentPadding, contentPadding)
        view.setOnApplyWindowInsetsListener { target, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                left = systemBars.left
                top = systemBars.top
                right = systemBars.right
                bottom = maxOf(systemBars.bottom, keyboard.bottom)
            } else {
                left = insets.systemWindowInsetLeft
                top = insets.systemWindowInsetTop
                right = insets.systemWindowInsetRight
                bottom = insets.systemWindowInsetBottom
            }
            target.setPadding(
                contentPadding + left,
                contentPadding + top,
                contentPadding + right,
                contentPadding + bottom,
            )
            insets
        }
        view.requestApplyInsets()
    }

    private companion object {
        const val requestNotifications = 1
        const val requestVpnPermission = 2
        const val savedConnectionRequest = "connection-requested"
    }
}
