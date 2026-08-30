package org.aurora.protocol.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AuroraVpnService : VpnService() {
    internal val commandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    internal val lifecycle = vpnProcessLifecycle.acquire()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = vpnServiceCommand(intent?.action)
        val statusBeforeCommand = vpnTunnelStatus.publication
        val handled = when (command) {
            VpnServiceCommand.CONNECT -> {
                val requestId = intent?.getLongExtra(connectVpnRequestIdExtra, 0L)
                if (vpnConnectRequestGate.claim(requestId)) {
                    startTunnel(startId)
                    true
                } else {
                    retainActiveConnectionOrStop(startId)
                    false
                }
            }
            VpnServiceCommand.DISCONNECT -> {
                stopTunnel(stopService = true, serviceStartId = startId)
                true
            }
            null -> {
                retainActiveConnectionOrStop(startId)
                false
            }
        }
        if (handled) {
            vpnTunnelStatus.publishCurrentIfUnchanged(statusBeforeCommand.revision)
        }
        return Service.START_NOT_STICKY
    }

    private fun retainActiveConnectionOrStop(serviceStartId: Int) {
        if (!lifecycle.shareActiveStart(serviceStartId)) {
            stopSelfResult(serviceStartId)
        }
    }

    override fun onRevoke() {
        stopTunnel(stopService = true)
        super.onRevoke()
    }

    override fun onDestroy() {
        try {
            stopTunnel(stopService = false)
        } finally {
            try {
                commandExecutor.shutdownNow()
                    .filterIsInstance<VpnConnectionCommand>()
                    .forEach { command -> command.discardIfQueued() }
            } finally {
                lifecycle.release()
                super.onDestroy()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    companion object {
        internal fun connect(context: Context, requestId: Long) {
            context.startForegroundService(
                Intent(context, AuroraVpnService::class.java)
                    .setAction(actionConnect)
                    .putExtra(connectVpnRequestIdExtra, requestId),
            )
        }

        internal fun disconnect(context: Context) {
            context.startService(Intent(context, AuroraVpnService::class.java).setAction(actionDisconnect))
        }

        const val actionConnect = connectVpnAction
        const val actionDisconnect = disconnectVpnAction
        const val notificationChannel = "aurora-vpn"
        const val notificationId = 101
        internal const val openAppRequestCode = 102
        internal const val disconnectRequestCode = 103
        const val tunnelMtu = 1280
        const val ipv4Address = "10.77.0.2"
        const val ipv4PrefixLength = 32
        const val ipv6Address = "fd77::2"
        const val ipv6PrefixLength = 128
        const val ipv4Dns = "100.64.0.1"
        const val ipv6Dns = "fd77::1"
    }
}
