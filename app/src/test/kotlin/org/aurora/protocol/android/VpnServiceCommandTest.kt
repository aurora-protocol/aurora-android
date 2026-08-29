package org.aurora.protocol.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    fun `pending service request waits for a newer status publication`() {
        val tracker = VpnServiceRequestTracker()
        tracker.begin(VpnServiceCommand.CONNECT, currentStatusRevision = 7)

        assertFalse(tracker.clearIfSuperseded(currentStatusRevision = 7))
        assertEquals(PendingVpnServiceCommand(VpnServiceCommand.CONNECT, 7), tracker.pending)
        assertTrue(tracker.clearIfSuperseded(currentStatusRevision = 8))
        assertNull(tracker.pending)
    }

    @Test
    fun `new command replaces the prior request at the current revision`() {
        val tracker = VpnServiceRequestTracker()
        tracker.begin(VpnServiceCommand.CONNECT, currentStatusRevision = 4)

        tracker.begin(VpnServiceCommand.DISCONNECT, currentStatusRevision = 4)

        assertEquals(PendingVpnServiceCommand(VpnServiceCommand.DISCONNECT, 4), tracker.pending)
    }

    @Test
    fun `restoration accepts only the same process session and status revision`() {
        val restored = VpnServiceRequestTracker(
            restoredCommand = VpnServiceCommand.CONNECT,
            restoredAfterStatusRevision = 3,
            restoredProcessSessionId = "same-process",
            currentStatusRevision = 3,
            currentProcessSessionId = "same-process",
        )
        val newerStatus = VpnServiceRequestTracker(
            restoredCommand = VpnServiceCommand.CONNECT,
            restoredAfterStatusRevision = 3,
            restoredProcessSessionId = "same-process",
            currentStatusRevision = 4,
            currentProcessSessionId = "same-process",
        )
        val newProcess = VpnServiceRequestTracker(
            restoredCommand = VpnServiceCommand.CONNECT,
            restoredAfterStatusRevision = 3,
            restoredProcessSessionId = "old-process",
            currentStatusRevision = 3,
            currentProcessSessionId = "new-process",
        )

        assertEquals(PendingVpnServiceCommand(VpnServiceCommand.CONNECT, 3), restored.pending)
        assertNull(newerStatus.pending)
        assertNull(newProcess.pending)
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
