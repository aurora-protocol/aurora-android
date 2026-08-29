package org.aurora.protocol.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `current observation closes the read then subscribe transition gap`() {
        val channel = VpnTunnelStatus()
        channel.publish(TunnelStatus.CONNECTING)
        val observed = mutableListOf<TunnelStatus>()

        val observation = channel.observeCurrent(observed::add)
        channel.publish(TunnelStatus.CONNECTED)

        assertEquals(TunnelStatus.CONNECTING, observation.status)
        assertEquals(listOf(TunnelStatus.CONNECTED), observed)
        observation.unsubscribe()
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
    fun `unchanged status publication acknowledges a command exactly once`() {
        val channel = VpnTunnelStatus()
        val observed = mutableListOf<TunnelStatusPublication>()
        channel.observeCurrentPublication(observed::add)
        val beforeCommand = channel.publication

        assertTrue(channel.publishCurrentIfUnchanged(beforeCommand.revision))
        assertFalse(channel.publishCurrentIfUnchanged(beforeCommand.revision))

        assertEquals(TunnelStatus.IDLE, channel.status)
        assertEquals(listOf(TunnelStatusPublication(TunnelStatus.IDLE, 1)), observed)
    }

    @Test
    fun `lifecycle transition wins over unchanged command acknowledgement`() {
        val channel = VpnTunnelStatus()
        val beforeCommand = channel.publication

        channel.publish(TunnelStatus.CONNECTING)

        assertFalse(channel.publishCurrentIfUnchanged(beforeCommand.revision))
        assertEquals(TunnelStatusPublication(TunnelStatus.CONNECTING, 1), channel.publication)
    }

    @Test
    fun `conditional publication succeeds only for the exact current publication`() {
        val channel = VpnTunnelStatus()
        val expected = channel.publication

        assertTrue(channel.publishIfCurrent(expected, TunnelStatus.CHECKING_PROVISIONING))
        assertFalse(channel.publishIfCurrent(expected, TunnelStatus.PROVISIONING_REQUIRED))

        assertEquals(TunnelStatusPublication(TunnelStatus.CHECKING_PROVISIONING, 1), channel.publication)
    }

    @Test
    fun `conditional publication returns its exact revision for chained work`() {
        val channel = VpnTunnelStatus()
        val expected = channel.publication

        val checking = channel.publishIfCurrentAndGet(expected, TunnelStatus.CHECKING_PROVISIONING)

        assertEquals(TunnelStatusPublication(TunnelStatus.CHECKING_PROVISIONING, 1), checking)
        assertNull(channel.publishIfCurrentAndGet(expected, TunnelStatus.PROVISIONING_REQUIRED))
    }

    @Test
    fun `newer lifecycle publication wins over a stale conditional result`() {
        val channel = VpnTunnelStatus()
        val checking = channel.publish(TunnelStatus.CHECKING_PROVISIONING)

        channel.publish(TunnelStatus.CONNECTING)

        assertFalse(channel.publishIfCurrent(checking, TunnelStatus.IDLE))
        assertEquals(TunnelStatusPublication(TunnelStatus.CONNECTING, 2), channel.publication)
    }

    @Test
    fun `revision distinguishes stale callbacks after an away and back transition`() {
        val channel = VpnTunnelStatus()
        val initial = channel.publication
        val renderState = TunnelStatusRenderState(initial)
        val observed = mutableListOf<TunnelStatusPublication>()
        val observation = channel.observeCurrentPublication(observed::add)

        channel.publish(TunnelStatus.IDLE)
        val staleIdle = channel.publication
        channel.publish(TunnelStatus.CONNECTING)
        channel.publish(TunnelStatus.IDLE)
        val currentIdle = channel.publication

        assertEquals(initial, observation.publication)
        assertEquals(TunnelStatus.IDLE, staleIdle.status)
        assertEquals(TunnelStatus.IDLE, currentIdle.status)
        assertNotEquals(staleIdle, currentIdle)
        assertEquals(listOf(1L, 2L, 3L), observed.map { it.revision })
        assertFalse(renderState.consumeIfCurrent(staleIdle, currentIdle))
        assertTrue(renderState.consumeIfCurrent(currentIdle, currentIdle))
        assertFalse(renderState.consumeIfCurrent(currentIdle, currentIdle))
        observation.unsubscribe()
    }

    @Test
    fun `local feedback suppresses only publications that already happened`() {
        val channel = VpnTunnelStatus()
        val renderState = TunnelStatusRenderState(channel.publication)
        channel.publish(TunnelStatus.CONNECTING)
        val alreadyPublished = channel.publication

        renderState.markLocalFeedback(alreadyPublished)

        assertFalse(renderState.consumeIfCurrent(alreadyPublished, channel.publication))
        channel.publish(TunnelStatus.CONNECTED)
        assertTrue(renderState.consumeIfCurrent(channel.publication, channel.publication))
    }

    @Test
    fun `each classification maps to a distinct status string`() {
        val texts = TunnelStatus.entries.map(::tunnelStatusText)

        assertEquals(TunnelStatus.entries.size, texts.toSet().size)
        assertNotEquals(R.string.status_connection_failed, tunnelStatusText(TunnelStatus.FAILED))
    }
}
