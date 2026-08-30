package org.aurora.protocol.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraVpnServiceStructureTest {
    @Test
    fun serviceKeepsLifecycleSeparateFromConnectionAndForeground() {
        val service = serviceSource()
        val connection = connectionSource()
        val connectionStart = connectionStartSource()
        val connectionRun = connectionRunSource()
        val connectionStop = connectionStopSource()
        val foreground = foregroundSource()
        val command = commandSource()

        assertTrue(service.contains("class AuroraVpnService : VpnService()"))
        assertTrue(service.contains("override fun onStartCommand("))
        assertTrue(service.contains("retainActiveConnectionOrStop(startId)"))
        assertTrue(service.contains("if (!lifecycle.shareActiveStart(serviceStartId))"))
        assertTrue(connection.contains("fun AuroraVpnService.startTunnel("))
        assertTrue(connection.contains("fun AuroraVpnService.runConnection("))
        assertTrue(connection.contains("fun AuroraVpnService.stopTunnel("))
        assertTrue(connectionStart.contains("fun AuroraVpnService.startTunnel("))
        assertTrue(connectionRun.contains("fun AuroraVpnService.runConnection("))
        assertTrue(connectionStop.contains("fun AuroraVpnService.stopTunnel("))
        assertFalse(connectionStart.contains("fun AuroraVpnService.runConnection("))
        assertFalse(connectionRun.contains("fun AuroraVpnService.stopTunnel("))
        assertTrue(foreground.contains("fun AuroraVpnService.enterVpnForeground()"))
        assertTrue(command.contains("class VpnConnectionCommand"))
        assertFalse(service.contains("fun AuroraVpnService.startTunnel("))
        assertFalse(service.contains("fun AuroraVpnService.enterVpnForeground()"))
        assertFalse(connectionStart.contains("override fun onStartCommand("))
    }

    @Test
    fun packetSessionAndTunnelDeviceStaySeparateFromService() {
        val session = sessionSource()
        val device = deviceSource()
        val service = serviceSource()

        assertTrue(session.contains("class CloseOnceNativePacketSession"))
        assertTrue(session.contains("class UnavailableProvisioningException"))
        assertTrue(device.contains("class FileDescriptorTunnelDevice"))
        assertFalse(service.contains("class CloseOnceNativePacketSession"))
        assertFalse(service.contains("class FileDescriptorTunnelDevice"))
    }

    @Test
    fun fullTunnelExcludesAuroraCarrierSocketsBeforeEstablishing() {
        val source = connectionEstablishSource()

        val ipv4DefaultRoute = source.indexOf(".addRoute(\"0.0.0.0\", 0)")
        val ipv6DefaultRoute = source.indexOf(".addRoute(\"::\", 0)")
        val selfExclusion = source.indexOf(".addDisallowedApplication(packageName)")
        val establish = source.indexOf(".establish()")

        assertTrue(ipv4DefaultRoute >= 0)
        assertTrue(ipv6DefaultRoute >= 0)
        assertTrue(selfExclusion > ipv4DefaultRoute)
        assertTrue(selfExclusion > ipv6DefaultRoute)
        assertTrue(establish > selfExclusion)
    }

    private fun serviceSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraVpnService.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraVpnService.kt",
    )

    private fun connectionSource(): String = listOf(
        connectionStartSource(),
        connectionRunSource(),
        connectionEstablishSource(),
        connectionStopSource(),
    ).joinToString("\n")

    private fun connectionStartSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraVpnServiceConnectionStart.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraVpnServiceConnectionStart.kt",
    )

    private fun connectionRunSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraVpnServiceConnectionRun.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraVpnServiceConnectionRun.kt",
    )

    private fun connectionEstablishSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraVpnServiceConnectionEstablish.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraVpnServiceConnectionEstablish.kt",
    )

    private fun connectionStopSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraVpnServiceConnectionStop.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraVpnServiceConnectionStop.kt",
    )

    private fun foregroundSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraVpnServiceForeground.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraVpnServiceForeground.kt",
    )

    private fun commandSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnConnectionCommand.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnConnectionCommand.kt",
    )

    private fun sessionSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/CloseOnceNativePacketSession.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/CloseOnceNativePacketSession.kt",
    )

    private fun deviceSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/FileDescriptorTunnelDevice.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/FileDescriptorTunnelDevice.kt",
    )

    private fun readFirstExisting(vararg relativePaths: String): String =
        relativePaths
            .map(::File)
            .firstOrNull(File::isFile)
            ?.readText()
            ?: throw IllegalStateException("source file is unavailable")
}
