package org.aurora.protocol.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningStorageStructureTest {
    @Test
    fun storageOperationTypesStaySeparateFromChannelAndCommand() {
        val types = typesSource()
        val operations = operationsSource()
        val command = commandSource()

        assertTrue(types.contains("enum class ProvisioningStorageOperation"))
        assertTrue(types.contains("data class ProvisioningStorageOperationPublication"))
        assertTrue(operations.contains("class ProvisioningStorageOperations"))
        assertTrue(operations.contains("fun begin(operation: ProvisioningStorageOperation)"))
        assertTrue(command.contains("class ProvisioningStorageCommand"))
        assertFalse(types.contains("class ProvisioningStorageOperations"))
        assertFalse(operations.contains("class ProvisioningStorageCommand"))
        assertFalse(command.contains("fun observeCurrent("))
    }

    @Test
    fun availabilityRestorerStaysSeparateFromRefreshPolicy() {
        val restorer = restorerCoreSource()
        val restorerState = restorerStateSource()
        val dispatch = dispatchSource()
        val policy = policySource()

        assertTrue(restorer.contains("class ProvisioningAvailabilityRestorer"))
        assertTrue(restorerState.contains("fun ProvisioningAvailabilityRestorer.recordImportedReservation("))
        assertTrue(restorerState.contains("fun ProvisioningAvailabilityRestorer.recordProvisioningRemoved("))
        assertTrue(restorerState.contains("fun ProvisioningAvailabilityRestorer.expireKnownReservation("))
        assertFalse(restorer.contains("fun ProvisioningAvailabilityRestorer.recordImportedReservation("))
        assertTrue(dispatch.contains("fun ProvisioningAvailabilityRestorer.dispatch("))
        assertTrue(dispatch.contains("fun ProvisioningAvailabilityRestorer.complete("))
        assertFalse(restorer.contains("fun ProvisioningAvailabilityRestorer.dispatch("))
        assertTrue(policy.contains("fun provisioningRefreshAllowed("))
        assertTrue(policy.contains("data class ProvisioningAvailabilityProbe"))
        assertFalse(restorer.contains("fun provisioningRefreshAllowed("))
        assertFalse(policy.contains("class ProvisioningAvailabilityRestorer"))
    }

    private fun typesSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/ProvisioningStorageOperationTypes.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/ProvisioningStorageOperationTypes.kt",
    )

    private fun operationsSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/ProvisioningStorageOperations.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/ProvisioningStorageOperations.kt",
    )

    private fun commandSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/ProvisioningStorageCommand.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/ProvisioningStorageCommand.kt",
    )

    private fun restorerSource(): String = listOf(
        restorerCoreSource(),
        restorerStateSource(),
    ).joinToString("\n")

    private fun restorerCoreSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/ProvisioningAvailabilityRestorer.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/ProvisioningAvailabilityRestorer.kt",
    )

    private fun restorerStateSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/ProvisioningAvailabilityRestorerState.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/ProvisioningAvailabilityRestorerState.kt",
    )

    private fun dispatchSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/ProvisioningAvailabilityRestorerDispatch.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/ProvisioningAvailabilityRestorerDispatch.kt",
    )

    private fun policySource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/ProvisioningRefreshPolicy.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/ProvisioningRefreshPolicy.kt",
    )

    private fun readFirstExisting(vararg relativePaths: String): String =
        relativePaths
            .map(::File)
            .firstOrNull(File::isFile)
            ?.readText()
            ?: throw IllegalStateException("source file is unavailable")
}
