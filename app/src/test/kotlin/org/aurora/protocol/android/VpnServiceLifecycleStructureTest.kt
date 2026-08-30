package org.aurora.protocol.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnServiceLifecycleStructureTest {
    @Test
    fun leaseWrapperStaysSeparateFromProcessLifecycle() {
        val lease = leaseSource()
        val process = processSource()

        assertTrue(lease.contains("class VpnServiceLifecycle"))
        assertTrue(lease.contains("fun start(serviceStartId: Int): VpnConnectionStart"))
        assertTrue(lease.contains("fun release()"))
        assertFalse(lease.contains("class VpnProcessLifecycle"))
        assertFalse(lease.contains("fun detachConnection("))
    }

    @Test
    fun connectionResultsStaySeparateFromLifecycleOwners() {
        val results = resultsSource()
        val lease = leaseSource()
        val process = processSource()

        assertTrue(results.contains("sealed interface VpnConnectionStart"))
        assertTrue(results.contains("sealed interface VpnConnectionStop"))
        assertFalse(results.contains("class VpnProcessLifecycle"))
        assertFalse(lease.contains("sealed interface VpnConnectionStart"))
        assertFalse(process.contains("sealed interface VpnConnectionStop"))
    }

    @Test
    fun processStateAndTeardownStaySeparateFromConnectionFlow() {
        val state = stateSource()
        val teardown = teardownSource()
        val process = processSource()
        val executors = executorsSource()

        assertTrue(state.contains("class ActiveConnection"))
        assertTrue(state.contains("class ActiveTeardown"))
        assertTrue(state.contains("fun terminalTunnelStatus("))
        assertTrue(teardown.contains("fun VpnProcessLifecycle.detachConnection("))
        assertTrue(teardown.contains("fun VpnProcessLifecycle.submitTeardown("))
        assertTrue(teardown.contains("fun VpnProcessLifecycle.finishTeardownIfComplete("))
        val teardownDetach = teardownDetachSource()
        val teardownSubmit = teardownSubmitSource()
        val teardownComplete = teardownCompleteSource()
        assertTrue(teardownDetach.contains("fun VpnProcessLifecycle.detachConnection("))
        assertTrue(teardownSubmit.contains("fun VpnProcessLifecycle.submitTeardown("))
        assertTrue(teardownComplete.contains("fun VpnProcessLifecycle.finishTeardownIfComplete("))
        assertFalse(teardownDetach.contains("fun VpnProcessLifecycle.submitTeardown("))
        assertFalse(teardownSubmit.contains("fun VpnProcessLifecycle.finishTeardownIfComplete("))
        assertTrue(executors.contains("fun newVpnTeardownExecutor("))
        val connection = connectionSource()
        val connectionRuntime = connectionRuntimeSource()
        val connectionWork = connectionWorkSource()
        val stop = stopSource()

        assertTrue(process.contains("fun acquire(): VpnServiceLifecycle"))
        assertTrue(connection.contains("fun VpnProcessLifecycle.start("))
        assertTrue(connection.contains("fun VpnProcessLifecycle.attachSession("))
        assertTrue(connectionRuntime.contains("fun VpnProcessLifecycle.promoteRuntime("))
        assertTrue(connectionRuntime.contains("fun VpnProcessLifecycle.markProvisioningUnavailable("))
        assertFalse(connection.contains("fun VpnProcessLifecycle.promoteRuntime("))
        assertFalse(connectionRuntime.contains("fun VpnProcessLifecycle.start("))
        assertTrue(connectionWork.contains("fun VpnProcessLifecycle.beginConnectionWork("))
        assertTrue(stop.contains("fun VpnProcessLifecycle.stop("))
        assertFalse(process.contains("fun VpnProcessLifecycle.start("))
        assertFalse(process.contains("fun VpnProcessLifecycle.beginConnectionWork("))
        assertFalse(process.contains("fun VpnProcessLifecycle.stop("))
        assertFalse(process.contains("fun VpnProcessLifecycle.detachConnection("))
        assertFalse(process.contains("fun terminalTunnelStatus("))
        assertFalse(teardown.contains("fun acquire(): VpnServiceLifecycle"))
        assertFalse(connection.contains("fun VpnProcessLifecycle.detachConnection("))
    }

    private fun leaseSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnServiceLifecycle.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnServiceLifecycle.kt",
    )

    private fun processSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycle.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycle.kt",
    )

    private fun resultsSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnConnectionResults.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnConnectionResults.kt",
    )

    private fun stateSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleState.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleState.kt",
    )

    private fun teardownSource(): String = listOf(
        teardownDetachSource(),
        teardownSubmitSource(),
        teardownCompleteSource(),
    ).joinToString("\n")

    private fun teardownDetachSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleTeardownDetach.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleTeardownDetach.kt",
    )

    private fun teardownSubmitSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleTeardownSubmit.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleTeardownSubmit.kt",
    )

    private fun teardownCompleteSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleTeardownComplete.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleTeardownComplete.kt",
    )

    private fun executorsSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleExecutors.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleExecutors.kt",
    )

    private fun connectionSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleConnection.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleConnection.kt",
    )

    private fun connectionRuntimeSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleConnectionRuntime.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleConnectionRuntime.kt",
    )

    private fun connectionWorkSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleConnectionWork.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleConnectionWork.kt",
    )

    private fun stopSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleStop.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnProcessLifecycleStop.kt",
    )

    private fun readFirstExisting(vararg relativePaths: String): String =
        relativePaths
            .map(::File)
            .firstOrNull(File::isFile)
            ?.readText()
            ?: throw IllegalStateException("source file is unavailable")
}
