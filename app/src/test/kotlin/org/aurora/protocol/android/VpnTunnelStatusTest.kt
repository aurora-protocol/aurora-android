package org.aurora.protocol.android

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnTunnelStatusTest {
    @Test
    fun throwingObserverDoesNotPreventLaterObservers() {
        val status = VpnTunnelStatus()
        val publication = TunnelStatusPublication(TunnelStatus.CONNECTED, 3)
        val seen = mutableListOf<TunnelStatusPublication>()

        status.notifyTunnelStatusObservers(
            publication,
            listOf(
                { throw AssertionError("broken observer") },
                { seen += it },
            ),
        )

        assertEquals(listOf(publication), seen)
    }
}
