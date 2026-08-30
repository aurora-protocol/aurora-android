package org.aurora.protocol.android.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTunnelRuntimeStructureTest {
    @Test
    fun runtimeKeepsCloseTeardownSeparateFromPacketWorkers() {
        val runtime = runtimeCoreSource()
        val close = closeSource()

        assertTrue(runtime.contains("class NativeTunnelRuntime"))
        assertTrue(runtime.contains("fun start()"))
        assertTrue(runtime.contains("private fun runIngress()"))
        assertTrue(runtime.contains("private fun runEgress()"))
        assertTrue(close.contains("fun NativeTunnelRuntime.transitionToClosed("))
        assertTrue(close.contains("fun NativeTunnelRuntime.finishClose("))
        assertTrue(close.contains("fun NativeTunnelRuntime.transitionToClosedPreserving("))
        assertFalse(runtime.contains("fun NativeTunnelRuntime.transitionToClosed("))
        assertFalse(runtime.contains("awaitWorkerTermination"))
        assertFalse(close.contains("private fun runIngress()"))
        assertFalse(close.contains("private fun runEgress()"))
    }

    private fun runtimeSource(): String = listOf(
        runtimeCoreSource(),
        closeSource(),
    ).joinToString("\n")

    private fun runtimeCoreSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/NativeTunnelRuntime.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/NativeTunnelRuntime.kt",
    )

    private fun closeSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/NativeTunnelRuntimeClose.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/NativeTunnelRuntimeClose.kt",
    )

    private fun readFirstExisting(vararg relativePaths: String): String =
        relativePaths
            .map(::File)
            .firstOrNull(File::isFile)
            ?.readText()
            ?: throw IllegalStateException("source file is unavailable")
}
