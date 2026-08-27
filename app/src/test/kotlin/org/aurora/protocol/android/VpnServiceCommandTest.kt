package org.aurora.protocol.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class VpnServiceCommandTest {
    @Test
    fun `explicit connect action starts a connection`() {
        assertEquals(VpnServiceCommand.CONNECT, vpnServiceCommand(connectVpnAction))
    }

    @Test
    fun `explicit disconnect action stops a connection`() {
        assertEquals(VpnServiceCommand.DISCONNECT, vpnServiceCommand(disconnectVpnAction))
    }

    @Test
    fun `missing and unknown actions fail closed`() {
        assertNull(vpnServiceCommand(null))
        assertNull(vpnServiceCommand("android.net.VpnService"))
        assertNull(vpnServiceCommand("org.aurora.protocol.android.action.UNKNOWN"))
    }

    @Test
    fun `service request failures are returned instead of escaping to the UI thread`() {
        assertNull(runVpnServiceRequest {})

        val failure = SecurityException("foreground service start denied")
        assertSame(failure, runVpnServiceRequest { throw failure })
    }

    @Test
    fun `cleanup runs every step and preserves the first failure`() {
        val firstFailure = IllegalStateException("session close failed")
        val secondFailure = IllegalStateException("foreground teardown failed")
        val completed = mutableListOf<String>()

        val failure = collectCleanupFailures(
            {
                completed += "session"
                throw firstFailure
            },
            { completed += "device" },
            {
                completed += "foreground"
                throw secondFailure
            },
            { completed += "service" },
        )

        assertSame(firstFailure, failure)
        assertEquals(listOf(secondFailure), failure?.suppressed?.toList())
        assertEquals(listOf("session", "device", "foreground", "service"), completed)
    }
}
