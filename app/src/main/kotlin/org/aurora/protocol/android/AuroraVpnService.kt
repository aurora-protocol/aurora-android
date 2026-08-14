package org.aurora.protocol.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.aurora.protocol.android.core.NativeSessionController
import org.aurora.protocol.android.core.NativeTunnelRuntime
import org.aurora.protocol.android.core.TunnelPacketDevice

class AuroraVpnService : VpnService() {
    private val commandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lifecycleLock = Any()
    private var runtime: NativeTunnelRuntime? = null
    private var connecting = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: actionConnect) {
            actionConnect -> startTunnel()
            actionDisconnect -> stopTunnel(stopService = true)
        }
        return Service.START_NOT_STICKY
    }

    override fun onRevoke() {
        stopTunnel(stopService = true)
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel(stopService = false)
        commandExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun startTunnel() {
        synchronized(lifecycleLock) {
            if (connecting || runtime != null) {
                return
            }
            connecting = true
        }
        enterForeground()
        commandExecutor.execute {
            var device: FileDescriptorTunnelDevice? = null
            val session = NativeSessionController()
            try {
                val reservation = (application as AuroraApplication).reservations.consume()
                    ?: throw IllegalStateException("no stored provisioning reservation")
                try {
                    session.establish(reservation.provisioning) {
                        device = establishTunnel()
                    }
                } finally {
                    reservation.close()
                }
                val establishedDevice = device ?: throw IllegalStateException("VPN interface is unavailable")
                val establishedRuntime = NativeTunnelRuntime(session, establishedDevice) { _ ->
                    stopTunnel(stopService = true)
                }
                synchronized(lifecycleLock) {
                    if (!connecting) {
                        establishedRuntime.close()
                        return@execute
                    }
                    runtime = establishedRuntime
                    connecting = false
                }
                establishedRuntime.start()
            } catch (_: Exception) {
                device?.close()
                session.close()
                synchronized(lifecycleLock) {
                    connecting = false
                }
                stopTunnel(stopService = true)
            }
        }
    }

    private fun establishTunnel(): FileDescriptorTunnelDevice {
        val descriptor = Builder()
            .setSession(applicationInfo.loadLabel(packageManager).toString())
            .setMtu(tunnelMtu)
            .setBlocking(true)
            .addAddress(ipv4Address, ipv4PrefixLength)
            .addAddress(ipv6Address, ipv6PrefixLength)
            .addDnsServer(ipv4Dns)
            .addDnsServer(ipv6Dns)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDisallowedApplication(packageName)
            .establish()
            ?: throw IllegalStateException("VPN permission was revoked")
        return FileDescriptorTunnelDevice(descriptor)
    }

    private fun stopTunnel(stopService: Boolean) {
        val existing = synchronized(lifecycleLock) {
            connecting = false
            val value = runtime
            runtime = null
            value
        }
        existing?.close()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        if (stopService) {
            stopSelf()
        }
    }

    private fun enterForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(notificationChannel, "Aurora VPN", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, notificationChannel)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Aurora")
            .setContentText("VPN active")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notification)
        }
    }

    private companion object {
        const val actionConnect = "org.aurora.protocol.android.action.CONNECT"
        const val actionDisconnect = "org.aurora.protocol.android.action.DISCONNECT"
        const val notificationChannel = "aurora-vpn"
        const val notificationId = 101
        const val tunnelMtu = 1280
        const val ipv4Address = "10.77.0.2"
        const val ipv4PrefixLength = 32
        const val ipv6Address = "fd77::2"
        const val ipv6PrefixLength = 128
        const val ipv4Dns = "100.64.0.1"
        const val ipv6Dns = "fd77::1"
    }
}

private class FileDescriptorTunnelDevice(
    private val descriptor: ParcelFileDescriptor,
) : TunnelPacketDevice {
    private val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)
    private val inputBuffer = ByteArray(maximumPacketBytes)
    private val outputLock = Any()

    override fun readPacket(): ByteArray? {
        try {
            val count = input.read(inputBuffer)
            if (count < 0) {
                return null
            }
            if (count == 0) {
                throw IOException("VPN interface returned an empty packet")
            }
            return inputBuffer.copyOf(count)
        } finally {
            inputBuffer.fill(0)
        }
    }

    override fun writePacket(packet: ByteArray) = synchronized(outputLock) {
        output.write(packet)
    }

    override fun close() {
        inputBuffer.fill(0)
        try {
            descriptor.close()
        } finally {
            try {
                input.close()
            } finally {
                output.close()
            }
        }
    }

    private companion object {
        const val maximumPacketBytes = 65535
    }
}
