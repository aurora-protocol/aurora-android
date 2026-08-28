package org.aurora.protocol.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VpnTunnelStatusTest {
    @Test
    fun `status starts idle and publications reach observers in order`() {
        val channel = VpnTunnelStatus()
        val observed = mutableListOf<TunnelStatus>()
        channel.observe(observed::add)

        channel.publish(TunnelStatus.CONNECTING)
        channel.publish(TunnelStatus.CONNECTED)

        assertEquals(TunnelStatus.CONNECTED, channel.status)
        assertEquals(listOf(TunnelStatus.CONNECTING, TunnelStatus.CONNECTED), observed)
    }

    @Test
    fun `unsubscribed observers stop receiving publications`() {
        val channel = VpnTunnelStatus()
        val fromReference = mutableListOf<TunnelStatus>()
        val fromLambda = mutableListOf<TunnelStatus>()
        // An adapted bound reference hashes its mutable receiver; unsubscribe
        // must still work after publications changed that receiver.
        val unsubscribeReference = channel.observe(fromReference::add)
        val unsubscribeLambda = channel.observe { update -> fromLambda += update }

        channel.publish(TunnelStatus.CONNECTING)
        unsubscribeReference()
        unsubscribeLambda()
        channel.publish(TunnelStatus.FAILED)

        assertEquals(TunnelStatus.FAILED, channel.status)
        assertEquals(listOf(TunnelStatus.CONNECTING), fromReference)
        assertEquals(listOf(TunnelStatus.CONNECTING), fromLambda)
    }

    @Test
    fun `a throwing observer breaks neither the channel nor later observers`() {
        val channel = VpnTunnelStatus()
        val observed = mutableListOf<TunnelStatus>()
        channel.observe { throw RuntimeException("observer crashed") }
        channel.observe(observed::add)

        channel.publish(TunnelStatus.CONNECTED)

        assertEquals(TunnelStatus.CONNECTED, channel.status)
        assertEquals(listOf(TunnelStatus.CONNECTED), observed)
    }

    @Test
    fun `each classification maps to a distinct status string`() {
        val texts = TunnelStatus.entries.map(::tunnelStatusText)

        assertEquals(TunnelStatus.entries.size, texts.toSet().size)
        assertNotEquals(R.string.status_connection_failed, tunnelStatusText(TunnelStatus.FAILED))
    }
}
