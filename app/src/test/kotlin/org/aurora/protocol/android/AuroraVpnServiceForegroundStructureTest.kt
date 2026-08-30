package org.aurora.protocol.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraVpnServiceForegroundStructureTest {
    @Test
    fun foregroundStartsBeforeConnectionWorkIsQueued() {
        val source = source("AuroraVpnServiceConnectionStart.kt")

        val foreground = source.indexOf("enterVpnForeground()")
        val connectionWork = source.indexOf("commandExecutor.execute(connectionCommand)")

        assertTrue(foreground >= 0)
        assertTrue(connectionWork > foreground)
    }

    @Test
    fun vpnNotificationIsImmediatePrivateAndNonBadging() {
        val source = source("AuroraVpnServiceForeground.kt")

        assertTrue(source.contains("setShowBadge(false)"))
        assertTrue(source.contains("lockscreenVisibility = Notification.VISIBILITY_PRIVATE"))
        assertTrue(source.contains(".setVisibility(Notification.VISIBILITY_PRIVATE)"))
        assertTrue(source.contains(".setLocalOnly(true)"))
        assertTrue(source.contains("Build.VERSION_CODES.S"))
        assertTrue(source.contains("Notification.FOREGROUND_SERVICE_IMMEDIATE"))
        assertTrue(source.contains("setAuthenticationRequired(true)"))
    }

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/org/aurora/protocol/android/$name"),
        File("app/src/main/kotlin/org/aurora/protocol/android/$name"),
    ).first(File::isFile).readText()
}
