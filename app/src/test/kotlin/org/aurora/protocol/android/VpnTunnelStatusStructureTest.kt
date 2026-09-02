package org.aurora.protocol.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTunnelStatusStructureTest {
    @Test
    fun tunnelStatusChannelKeepsRevisionGatingAPIs() {
        val source = tunnelStatusSources()

        assertTrue(source.contains("fun VpnTunnelStatus.publishIfCurrent("))
        assertTrue(source.contains("fun VpnTunnelStatus.publishIfCurrentAndGet("))
        assertTrue(source.contains("fun VpnTunnelStatus.publishCurrentIfUnchanged("))
        assertTrue(source.contains("fun VpnTunnelStatus.observeCurrentPublication("))
    }

    @Test
    fun tunnelStatusRenderStateStaysSeparateFromChannel() {
        val channel = channelSource()
        val publish = publishSource()
        val observation = observationSource()
        val renderState = renderStateSource()
        val publication = publicationSource()

        assertTrue(channel.contains("class VpnTunnelStatus"))
        assertTrue(publish.contains("fun VpnTunnelStatus.publish("))
        assertTrue(observation.contains("fun VpnTunnelStatus.observeCurrentPublication("))
        assertFalse(channel.contains("fun VpnTunnelStatus.publishIfCurrent("))
        assertFalse(publish.contains("fun VpnTunnelStatus.observeCurrentPublication("))
        assertFalse(channel.contains("class TunnelStatusRenderState"))
        assertTrue(renderState.contains("class TunnelStatusRenderState"))
        assertTrue(renderState.contains("fun consumeIfCurrent("))
        assertTrue(renderState.contains("fun markLocalFeedback("))
        assertTrue(renderState.contains("TunnelStatus.PROVISIONING_EXPIRED -> R.string.status_provisioning_expired"))
        assertTrue(publication.contains("data class TunnelStatusPublication"))
        assertTrue(publication.contains("data class TunnelStatusPublicationObservation"))
    }

    @Test
    fun tunnelStatusObserversRemainFaultIsolated() {
        val source = tunnelStatusSources()

        assertTrue(source.contains("catch (error: Throwable)"))
        assertTrue(source.contains("AuroraLog.debug(\"tunnel status observation\", error)"))
        assertTrue(source.contains("observers.removeIf { it === observer }"))
        assertFalse(source.contains("class AuroraActivity"))
    }

    private fun tunnelStatusSources(): String = listOf(
        channelSource(),
        publishSource(),
        observationSource(),
    ).joinToString("\n")

    private fun channelSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnTunnelStatus.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnTunnelStatus.kt",
    )

    private fun publishSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnTunnelStatusPublish.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnTunnelStatusPublish.kt",
    )

    private fun observationSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnTunnelStatusObservation.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnTunnelStatusObservation.kt",
    )

    private fun renderStateSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/TunnelStatusRenderState.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/TunnelStatusRenderState.kt",
    )

    private fun publicationSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/TunnelStatusPublication.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/TunnelStatusPublication.kt",
    )

    private fun readFirstExisting(vararg relativePaths: String): String =
        relativePaths
            .map(::File)
            .firstOrNull(File::isFile)
            ?.readText()
            ?: throw IllegalStateException("source file is unavailable")
}
