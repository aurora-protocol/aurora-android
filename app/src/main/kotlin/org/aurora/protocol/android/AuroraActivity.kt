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
import org.aurora.protocol.android.core.ProvisioningImport

class AuroraActivity : Activity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var importField: EditText
    private lateinit var status: TextView

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
        if (requestCode == requestVpnPermission) {
            if (resultCode == RESULT_OK) {
                AuroraVpnService.connect(this)
                status.text = "Connecting"
            } else {
                status.text = "VPN permission required"
            }
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
        status = TextView(this).apply { text = "Ready" }
        layout.addView(importField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layout.addView(commandButton("Import") { importProvisioning() })
        layout.addView(commandButton("Connect") { connect() })
        layout.addView(commandButton("Disconnect") {
            AuroraVpnService.disconnect(this)
            status.text = "Disconnected"
        })
        layout.addView(status)
        return layout
    }

    private fun commandButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun importProvisioning() {
        val encoded = importField.text.toString()
        importField.text?.clear()
        worker.execute {
            try {
                val request = ProvisioningImport.decode(encoded)
                (application as AuroraApplication).reservations.reserveAndPersist(request, System.currentTimeMillis() / 1_000)
                runOnUiThread { status.text = "Ready" }
            } catch (_: Exception) {
                runOnUiThread { status.text = "Import failed" }
            }
        }
    }

    private fun connect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestNotifications)
        }
        val preparation = VpnService.prepare(this)
        if (preparation == null) {
            AuroraVpnService.connect(this)
            status.text = "Connecting"
        } else {
            startActivityForResult(preparation, requestVpnPermission)
        }
    }

    private companion object {
        const val requestNotifications = 1
        const val requestVpnPermission = 2
    }
}
