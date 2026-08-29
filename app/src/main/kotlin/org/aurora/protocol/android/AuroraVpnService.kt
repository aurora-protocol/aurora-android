package org.aurora.protocol.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.aurora.protocol.android.core.AuroraLog
import org.aurora.protocol.android.core.NativePacketSession
import org.aurora.protocol.android.core.NativeSessionController
import org.aurora.protocol.android.core.NativeTunnelRuntime
import org.aurora.protocol.android.core.TunnelPacketDevice

internal val vpnTunnelStatus = VpnTunnelStatus()

private val vpnProcessLifecycle = VpnProcessLifecycle(
    onTeardownFailure = { error -> AuroraLog.debug("tunnel resource cleanup", error) },
    tunnelStatus = vpnTunnelStatus,
)

internal class VpnConnectionCommand(
    private val lifecycle: VpnServiceLifecycle,
    private val generation: Long,
    private val work: () -> Unit,
) : Runnable {
    private val claimed = AtomicBoolean()

    override fun run() {
        if (!claimed.compareAndSet(false, true) || !lifecycle.beginConnectionWork(generation)) {
            return
        }
        try {
            work()
        } finally {
            lifecycle.finishConnectionWork(generation)
        }
    }

    /** Marks work proven never to have started by rejection or ExecutorService.shutdownNow(). */
    fun discardIfQueued() {
        if (claimed.compareAndSet(false, true)) {
            lifecycle.discardConnectionWork(generation)
        }
    }
}

class AuroraVpnService : VpnService() {
    private val commandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lifecycle = vpnProcessLifecycle.acquire()

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

    private fun startTunnel(serviceStartId: Int) {
        val ownGeneration = when (val start = lifecycle.start(serviceStartId)) {
            is VpnConnectionStart.Accepted -> start.generation
            VpnConnectionStart.Shared -> return
            VpnConnectionStart.Rejected -> {
                collectCleanupFailures({ stopSelfResult(serviceStartId) })?.let { error ->
                    AuroraLog.debug("rejected tunnel start cleanup", error)
                }
                return
            }
        }
        val connectionCommand = VpnConnectionCommand(lifecycle, ownGeneration) {
            runConnection(ownGeneration)
        }
        try {
            enterForeground()
            commandExecutor.execute(connectionCommand)
        } catch (error: RuntimeException) {
            connectionCommand.discardIfQueued()
            AuroraLog.debug("tunnel startup", error)
            stopTunnel(
                stopService = true,
                expectedGeneration = ownGeneration,
                failed = true,
            )
        }
    }

    private fun runConnection(ownGeneration: Long) {
        var device: FileDescriptorTunnelDevice? = null
        var candidateRuntime: NativeTunnelRuntime? = null
        var session: NativeSessionController? = null
        var sessionOwner: CloseOnceNativePacketSession? = null
        try {
            val createdSession = NativeSessionController()
            session = createdSession
            val ownedSession = CloseOnceNativePacketSession(createdSession)
            sessionOwner = ownedSession
            if (!lifecycle.attachSession(ownGeneration, ownedSession)) {
                collectCleanupFailures(ownedSession::close)?.let { error ->
                    AuroraLog.debug("cancelled tunnel cleanup", error)
                }
                return
            }

            val reservation = (application as AuroraApplication).reservations.consume()
                ?: throw IllegalStateException("no stored provisioning reservation")
            try {
                createdSession.establish(reservation.provisioning) {
                    device = establishTunnel()
                }
            } finally {
                reservation.close()
            }
            val establishedDevice = device ?: throw IllegalStateException("VPN interface is unavailable")
            val establishedRuntime = NativeTunnelRuntime(ownedSession, establishedDevice) { error ->
                AuroraLog.debug("tunnel runtime", error)
                stopTunnel(
                    stopService = true,
                    expectedGeneration = ownGeneration,
                    failed = true,
                )
            }
            candidateRuntime = establishedRuntime
            if (!lifecycle.promoteRuntime(ownGeneration, ownedSession, establishedRuntime)) {
                collectCleanupFailures(establishedRuntime::close)?.let { error ->
                    AuroraLog.debug("cancelled tunnel cleanup", error)
                }
                return
            }
            establishedRuntime.start()
        } catch (error: Throwable) {
            if (candidateRuntime == null) {
                collectCleanupFailures(
                    { device?.close() },
                    { sessionOwner?.close() ?: session?.close() },
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
                failed = true,
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
        failed: Boolean = false,
    ) {
        when (val stop = lifecycle.stop(expectedGeneration, serviceStartId, failed)) {
            VpnConnectionStop.Ignored -> return
            is VpnConnectionStop.AlreadyInProgress -> {
                if (stopService && stop.serviceStartId != null) {
                    collectCleanupFailures({ stopSelfResult(stop.serviceStartId) })?.let { error ->
                        AuroraLog.debug("tunnel service cleanup", error)
                    }
                }
            }
            is VpnConnectionStop.Started -> {
                val failure = try {
                    collectCleanupFailures(
                        { stopForeground(STOP_FOREGROUND_REMOVE) },
                        {
                            if (stopService) {
                                if (stop.serviceStartId == null) {
                                    stopSelf()
                                } else {
                                    stopSelfResult(stop.serviceStartId)
                                }
                            }
                        },
                    )
                } finally {
                    lifecycle.finishLifecycleStop(stop.teardownId)
                }
                failure?.let { error -> AuroraLog.debug("tunnel service cleanup", error) }
            }
        }
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
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    openAppRequestCode,
                    Intent(this, AuroraActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_aurora),
                    getString(R.string.action_disconnect),
                    PendingIntent.getService(
                        this,
                        disconnectRequestCode,
                        Intent(this, AuroraVpnService::class.java).setAction(actionDisconnect),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build(),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
        private const val openAppRequestCode = 102
        private const val disconnectRequestCode = 103
        const val tunnelMtu = 1280
        const val ipv4Address = "10.77.0.2"
        const val ipv4PrefixLength = 32
        const val ipv6Address = "fd77::2"
        const val ipv6PrefixLength = 128
        const val ipv4Dns = "100.64.0.1"
        const val ipv6Dns = "fd77::1"
    }
}

internal class CloseOnceNativePacketSession(
    private val delegate: NativePacketSession,
) : NativePacketSession {
    private val closeStarted = AtomicBoolean()
    private val closeCompletion = CountDownLatch(1)
    private val closeFailure = AtomicReference<Throwable?>()

    override fun ingressLocalPacket(packet: ByteArray): List<ByteArray> = delegate.ingressLocalPacket(packet)

    override fun nextLocalPacket(): ByteArray = delegate.nextLocalPacket()

    override fun close() {
        if (closeStarted.compareAndSet(false, true)) {
            try {
                delegate.close()
            } catch (error: Throwable) {
                closeFailure.set(error)
            } finally {
                closeCompletion.countDown()
            }
        } else {
            awaitCloseCompletion()
        }
        closeFailure.get()?.let { throw it }
    }

    private fun awaitCloseCompletion() {
        var interruption: InterruptedException? = null
        while (true) {
            try {
                closeCompletion.await()
                break
            } catch (error: InterruptedException) {
                val first = interruption
                if (first == null) {
                    interruption = error
                } else if (first !== error) {
                    first.addSuppressed(error)
                }
            }
        }
        interruption?.let { error ->
            Thread.currentThread().interrupt()
            closeFailure.get()?.let { failure ->
                if (failure !== error) {
                    failure.addSuppressed(error)
                }
                throw failure
            }
            throw error
        }
    }
}

private class FileDescriptorTunnelDevice(
    descriptor: ParcelFileDescriptor,
) : TunnelPacketDevice {
    private val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)
    private val teardown = TunnelDeviceTeardown(descriptor, input, output)
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
        teardown.close()
    }

    private companion object {
        const val maximumPacketBytes = 65535
    }
}
