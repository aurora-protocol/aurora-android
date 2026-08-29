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
        tracker.begin(
            VpnServiceCommand.CONNECT,
            currentStatusRevision = 7,
            currentUptimeMillis = 100,
            connectRequestId = 11,
        )

        assertFalse(tracker.clearIfAcknowledged(TunnelStatusPublication(TunnelStatus.IDLE, 7)))
        assertEquals(
            PendingVpnServiceCommand(VpnServiceCommand.CONNECT, 7, 100 + vpnServiceRequestTimeoutMillis, 11),
            tracker.pending,
        )
        assertTrue(tracker.clearIfAcknowledged(TunnelStatusPublication(TunnelStatus.CONNECTING, 8)))
        assertNull(tracker.pending)
    }

    @Test
    fun `unacknowledged service request expires only at its monotonic deadline`() {
        val tracker = VpnServiceRequestTracker()
        tracker.begin(
            VpnServiceCommand.CONNECT,
            currentStatusRevision = 7,
            currentUptimeMillis = 100,
            connectRequestId = 11,
        )

        assertNull(
            tracker.expireIfUnacknowledged(
                currentStatus = TunnelStatusPublication(TunnelStatus.IDLE, 7),
                currentUptimeMillis = 100 + vpnServiceRequestTimeoutMillis - 1,
            ),
        )
        assertEquals(
            PendingVpnServiceCommand(VpnServiceCommand.CONNECT, 7, 100 + vpnServiceRequestTimeoutMillis, 11),
            tracker.expireIfUnacknowledged(
                currentStatus = TunnelStatusPublication(TunnelStatus.IDLE, 7),
                currentUptimeMillis = 100 + vpnServiceRequestTimeoutMillis,
            ),
        )
        assertNull(tracker.pending)
    }

    @Test
    fun `disconnect waits through preceding connection progress`() {
        val tracker = VpnServiceRequestTracker()
        tracker.begin(
            VpnServiceCommand.DISCONNECT,
            currentStatusRevision = 7,
            currentUptimeMillis = 100,
            connectRequestId = null,
        )

        assertFalse(
            tracker.clearIfAcknowledged(TunnelStatusPublication(TunnelStatus.CONNECTED, 8)),
        )
        assertEquals(
            PendingVpnServiceCommand(VpnServiceCommand.DISCONNECT, 7, 100 + vpnServiceRequestTimeoutMillis, null),
            tracker.pending,
        )
        assertTrue(
            tracker.clearIfAcknowledged(TunnelStatusPublication(TunnelStatus.DISCONNECTING, 9)),
        )
        assertNull(tracker.pending)
    }

    @Test
    fun `provisioning-required terminal status acknowledges disconnect`() {
        val tracker = VpnServiceRequestTracker()
        tracker.begin(
            VpnServiceCommand.DISCONNECT,
            currentStatusRevision = 7,
            currentUptimeMillis = 100,
            connectRequestId = null,
        )

        assertTrue(
            tracker.clearIfAcknowledged(
                TunnelStatusPublication(TunnelStatus.PROVISIONING_REQUIRED, 8),
            ),
        )
        assertNull(tracker.pending)
    }

    @Test
    fun `expired-provisioning terminal status acknowledges disconnect`() {
        val tracker = VpnServiceRequestTracker()
        tracker.begin(
            VpnServiceCommand.DISCONNECT,
            currentStatusRevision = 7,
            currentUptimeMillis = 100,
            connectRequestId = null,
        )

        assertTrue(
            tracker.clearIfAcknowledged(
                TunnelStatusPublication(TunnelStatus.PROVISIONING_EXPIRED, 8),
            ),
        )
        assertNull(tracker.pending)
    }

    @Test
    fun `new command replaces the prior request at the current revision`() {
        val tracker = VpnServiceRequestTracker()
        tracker.begin(
            VpnServiceCommand.CONNECT,
            currentStatusRevision = 4,
            currentUptimeMillis = 100,
            connectRequestId = 11,
        )

        tracker.begin(
            VpnServiceCommand.DISCONNECT,
            currentStatusRevision = 4,
            currentUptimeMillis = 200,
            connectRequestId = null,
        )

        assertEquals(
            PendingVpnServiceCommand(VpnServiceCommand.DISCONNECT, 4, 200 + vpnServiceRequestTimeoutMillis, null),
            tracker.pending,
        )
    }

    @Test
    fun `restoration accepts only the same process session and status revision`() {
        val restored = VpnServiceRequestTracker(
            restoredCommand = VpnServiceCommand.CONNECT,
            restoredAfterStatusRevision = 3,
            restoredTimeoutAtUptimeMillis = 500,
            restoredConnectRequestId = 11,
            restoredProcessSessionId = "same-process",
            currentStatusRevision = 3,
            currentProcessSessionId = "same-process",
        )
        val newerStatus = VpnServiceRequestTracker(
            restoredCommand = VpnServiceCommand.CONNECT,
            restoredAfterStatusRevision = 3,
            restoredTimeoutAtUptimeMillis = 500,
            restoredConnectRequestId = 11,
            restoredProcessSessionId = "same-process",
            currentStatusRevision = 4,
            currentProcessSessionId = "same-process",
        )
        val newProcess = VpnServiceRequestTracker(
            restoredCommand = VpnServiceCommand.CONNECT,
            restoredAfterStatusRevision = 3,
            restoredTimeoutAtUptimeMillis = 500,
            restoredConnectRequestId = 11,
            restoredProcessSessionId = "old-process",
            currentStatusRevision = 3,
            currentProcessSessionId = "new-process",
        )
        val malformedRequestId = VpnServiceRequestTracker(
            restoredCommand = VpnServiceCommand.CONNECT,
            restoredAfterStatusRevision = 3,
            restoredTimeoutAtUptimeMillis = 500,
            restoredConnectRequestId = 0,
            restoredProcessSessionId = "same-process",
            currentStatusRevision = 3,
            currentProcessSessionId = "same-process",
        )

        assertEquals(PendingVpnServiceCommand(VpnServiceCommand.CONNECT, 3, 500, 11), restored.pending)
        assertNull(newerStatus.pending)
        assertNull(newProcess.pending)
        assertNull(malformedRequestId.pending)
    }

    @Test
    fun `invalidated and duplicate connect requests fail closed while a retry remains valid`() {
        val gate = VpnConnectRequestGate()
        val timedOut = gate.issue()
        gate.invalidate(timedOut)

        assertFalse(gate.claim(timedOut))

        val retry = gate.issue()
        assertTrue(gate.claim(retry))
        assertFalse(gate.claim(retry))
    }

    @Test
    fun `newest claimed connect supersedes older and unissued requests`() {
        val gate = VpnConnectRequestGate()
        val first = gate.issue()
        val second = gate.issue()

        assertTrue(gate.claim(second))
        assertFalse(gate.claim(first))
        assertFalse(gate.claim(second + 1))
        assertFalse(gate.claim(null))
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
