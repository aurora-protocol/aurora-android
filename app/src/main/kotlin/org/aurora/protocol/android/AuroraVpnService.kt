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
import org.aurora.protocol.android.core.AuroraLog
import org.aurora.protocol.android.core.NativeSessionController
import org.aurora.protocol.android.core.NativeTunnelRuntime
import org.aurora.protocol.android.core.TunnelPacketDevice

class AuroraVpnService : VpnService() {
    private val commandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lifecycleLock = Any()
    private var runtime: NativeTunnelRuntime? = null
    private var connectingSession: NativeSessionController? = null
    private var connecting = false
    private var connectionGeneration = 0L
    private var connectionStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (vpnServiceCommand(intent?.action)) {
            VpnServiceCommand.CONNECT -> startTunnel(startId)
            VpnServiceCommand.DISCONNECT -> stopTunnel(stopService = true, serviceStartId = startId)
            null -> stopSelfResult(startId)
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

    private fun startTunnel(serviceStartId: Int) {
        val ownGeneration = synchronized(lifecycleLock) {
            if (connecting || runtime != null) {
                // The work is shared, but this newer start request now owns its lifecycle.
                connectionStartId = serviceStartId
                return
            }
            connecting = true
            connectionStartId = serviceStartId
            ++connectionGeneration
        }
        try {
            enterForeground()
            commandExecutor.execute {
                var device: FileDescriptorTunnelDevice? = null
                var candidateRuntime: NativeTunnelRuntime? = null
                val session = NativeSessionController()
                val acceptedSession = synchronized(lifecycleLock) {
                    if (!isCurrentConnection(ownGeneration)) {
                        false
                    } else {
                        connectingSession = session
                        true
                    }
                }
                if (!acceptedSession) {
                    collectCleanupFailures(session::close)?.let {
                        AuroraLog.debug("cancelled tunnel cleanup", it)
                    }
                    return@execute
                }
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
                    val establishedRuntime = NativeTunnelRuntime(session, establishedDevice) { error ->
                        AuroraLog.debug("tunnel runtime", error)
                        stopTunnel(
                            stopService = true,
                            expectedGeneration = ownGeneration,
                        )
                    }
                    candidateRuntime = establishedRuntime
                    val acceptedRuntime = synchronized(lifecycleLock) {
                        if (!isCurrentConnection(ownGeneration) || connectingSession !== session) {
                            false
                        } else {
                            runtime = establishedRuntime
                            connectingSession = null
                            connecting = false
                            true
                        }
                    }
                    if (!acceptedRuntime) {
                        collectCleanupFailures(establishedRuntime::close)?.let {
                            AuroraLog.debug("cancelled tunnel cleanup", it)
                        }
                        return@execute
                    }
                    establishedRuntime.start()
                } catch (error: Throwable) {
                    if (candidateRuntime == null) {
                        collectCleanupFailures(
                            { device?.close() },
                            session::close,
                        )?.let { cleanupFailure ->
                            if (cleanupFailure !== error) {
                                error.addSuppressed(cleanupFailure)
                            }
                        }
                    }
                    AuroraLog.debug("tunnel establishment", error)
                    stopTunnel(
                        stopService = true,
                        expectedGeneration = ownGeneration,
                    )
                }
            }
        } catch (error: RuntimeException) {
            AuroraLog.debug("tunnel startup", error)
            stopTunnel(
                stopService = true,
                expectedGeneration = ownGeneration,
            )
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

    private fun stopTunnel(
        stopService: Boolean,
        expectedGeneration: Long? = null,
        serviceStartId: Int? = null,
    ) {
        val teardown = synchronized(lifecycleLock) {
            if (expectedGeneration != null && connectionGeneration != expectedGeneration) {
                return
            }
            val teardownGeneration = ++connectionGeneration
            val teardownStartId = serviceStartId ?: connectionStartId.takeIf { it > 0 }
            connecting = false
            val resources = runtime to connectingSession
            runtime = null
            connectingSession = null
            connectionStartId = 0
            Triple(teardownGeneration, resources, teardownStartId)
        }
        val (teardownGeneration, resources, teardownStartId) = teardown
        val (runtimeToClose, sessionToClose) = resources
        val resourceFailure = collectCleanupFailures(
            { runtimeToClose?.close() },
            { sessionToClose?.close() },
        )
        val lifecycleFailure = synchronized(lifecycleLock) {
            // A newer CONNECT owns both the foreground notification and service start.
            if (connectionGeneration != teardownGeneration) {
                null
            } else {
                collectCleanupFailures(
                    { stopForeground(STOP_FOREGROUND_REMOVE) },
                    {
                        if (stopService) {
                            if (teardownStartId == null) {
                                stopSelf()
                            } else {
                                stopSelfResult(teardownStartId)
                            }
                        }
                    },
                )
            }
        }
        val failure = when {
            resourceFailure == null -> lifecycleFailure
            lifecycleFailure == null || resourceFailure === lifecycleFailure -> resourceFailure
            else -> resourceFailure.apply {
                addSuppressed(lifecycleFailure)
            }
        }
        failure?.let { AuroraLog.debug("tunnel cleanup", it) }
    }

    private fun isCurrentConnection(generation: Long): Boolean {
        return connecting && connectionGeneration == generation
    }

    private fun enterForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                notificationChannel,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = Notification.Builder(this, notificationChannel)
            .setSmallIcon(R.drawable.ic_aurora)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_active))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notification)
        }
    }

    companion object {
        internal fun connect(context: Context) {
            context.startForegroundService(Intent(context, AuroraVpnService::class.java).setAction(actionConnect))
        }

        internal fun disconnect(context: Context) {
            context.startService(Intent(context, AuroraVpnService::class.java).setAction(actionDisconnect))
        }

        const val actionConnect = connectVpnAction
        const val actionDisconnect = disconnectVpnAction
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
