package org.aurora.protocol.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import org.aurora.protocol.android.core.ProvisioningImport

class AuroraActivity : Activity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var importField: EditText
    private lateinit var importButton: Button
    private lateinit var connectButton: Button
    private lateinit var status: TextView
    private val requestState = ConnectionRequestState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
    }

    override fun onDestroy() {
        worker.shutdownNow()
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
        if (requestCode == requestNotifications && requestState.connectRequested) {
            requestVpnPreparation()
        }
    }

    private fun buildContent(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        importField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(false)
            minLines = 4
        }
        status = TextView(this).apply { setText(R.string.status_ready) }
        layout.addView(importField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        importButton = commandButton(R.string.action_import) { importProvisioning() }
        connectButton = commandButton(R.string.action_connect) { connect() }
        layout.addView(importButton)
        layout.addView(connectButton)
        layout.addView(commandButton(R.string.action_disconnect) {
            requestState.cancelConnectionRequest()
            refreshControls()
            AuroraVpnService.disconnect(this)
            status.setText(R.string.status_disconnected)
        })
        layout.addView(status)
        return layout
    }

    private fun commandButton(label: Int, action: () -> Unit): Button = Button(this).apply {
        setText(label)
        setOnClickListener { action() }
    }

    private fun importProvisioning() {
        if (!requestState.beginImport()) {
            return
        }
        val encoded = importField.text.toString()
        importField.text?.clear()
        status.setText(R.string.status_importing)
        refreshControls()
        try {
            worker.execute {
                val message = try {
                    val request = ProvisioningImport.decode(encoded)
                    (application as AuroraApplication).reservations.reserveAndPersist(request, System.currentTimeMillis() / 1_000)
                    getString(R.string.status_ready)
                } catch (_: Exception) {
                    getString(R.string.status_import_failed)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    requestState.completeImport()
                    status.text = message
                    refreshControls()
                }
            }
        } catch (_: RejectedExecutionException) {
            requestState.completeImport()
            status.setText(R.string.status_import_failed)
            refreshControls()
        }
    }

    private fun connect() {
        if (!requestState.beginConnectionRequest()) {
            return
        }
        refreshControls()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestNotifications)
            return
        }
        requestVpnPreparation()
    }

    private fun requestVpnPreparation() {
        if (!requestState.connectRequested) {
            return
        }
        val preparation = VpnService.prepare(this)
        if (preparation == null) {
            if (requestState.consumeConnectionRequest()) {
                refreshControls()
                startConnection()
            }
        } else {
            startActivityForResult(preparation, requestVpnPermission)
        }
    }

    private fun startConnection() {
        AuroraVpnService.connect(this)
        status.setText(R.string.status_connecting)
    }

    private fun refreshControls() {
        importButton.isEnabled = !requestState.importInProgress && !requestState.connectRequested
        connectButton.isEnabled = !requestState.importInProgress && !requestState.connectRequested
    }

    private companion object {
        const val requestNotifications = 1
        const val requestVpnPermission = 2
    }
}
