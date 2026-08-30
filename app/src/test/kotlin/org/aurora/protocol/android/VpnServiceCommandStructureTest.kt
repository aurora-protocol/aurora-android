package org.aurora.protocol.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnServiceCommandStructureTest {
    @Test
    fun commandTypesStaySeparateFromTrackerAndCleanup() {
        val constants = constantsSource()
        val types = typesSource()
        val gate = gateSource()
        val tracker = trackerCoreSource()
        val cleanup = cleanupSource()

        assertTrue(constants.contains("const val connectVpnAction"))
        assertTrue(types.contains("enum class VpnServiceCommand"))
        assertTrue(types.contains("fun vpnServiceCommand(action: String?)"))
        assertTrue(gate.contains("class VpnConnectRequestGate"))
        assertTrue(tracker.contains("class VpnServiceRequestTracker"))
        assertTrue(tracker.contains("fun begin("))
        val trackerAcknowledgment = trackerAcknowledgmentSource()
        assertTrue(trackerAcknowledgment.contains("fun VpnServiceRequestTracker.clearIfAcknowledged("))
        assertTrue(trackerAcknowledgment.contains("fun VpnServiceRequestTracker.expireIfUnacknowledged("))
        assertTrue(trackerAcknowledgment.contains("fun PendingVpnServiceCommand.isAcknowledgedBy("))
        assertFalse(tracker.contains("fun VpnServiceRequestTracker.clearIfAcknowledged("))
        assertTrue(cleanup.contains("fun collectCleanupFailures("))
        assertFalse(types.contains("class VpnConnectRequestGate"))
        assertFalse(tracker.contains("fun collectCleanupFailures("))
        assertFalse(cleanup.contains("class VpnServiceRequestTracker"))
    }

    private fun constantsSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnServiceCommandConstants.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnServiceCommandConstants.kt",
    )

    private fun typesSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnServiceCommandTypes.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnServiceCommandTypes.kt",
    )

    private fun gateSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnConnectRequestGate.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnConnectRequestGate.kt",
    )

    private fun trackerSource(): String = listOf(
        trackerCoreSource(),
        trackerAcknowledgmentSource(),
    ).joinToString("\n")

    private fun trackerCoreSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnServiceRequestTracker.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnServiceRequestTracker.kt",
    )

    private fun trackerAcknowledgmentSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnServiceRequestTrackerAcknowledgment.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnServiceRequestTrackerAcknowledgment.kt",
    )

    private fun cleanupSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/VpnServiceCleanup.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/VpnServiceCleanup.kt",
    )

    private fun readFirstExisting(vararg relativePaths: String): String =
        relativePaths
            .map(::File)
            .firstOrNull(File::isFile)
            ?.readText()
            ?: throw IllegalStateException("source file is unavailable")
}
