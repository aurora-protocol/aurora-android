package org.aurora.protocol.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningStorageOperationsTest {
    @Test
    fun beginsAndCompletesAStorageOperationLease() {
        val operations = ProvisioningStorageOperations()

        val lease = operations.begin(ProvisioningStorageOperation.REMOVING)
        assertTrue(lease != null)

        assertNull(operations.begin(ProvisioningStorageOperation.IMPORTING))
        assertEquals(ProvisioningStorageOperation.REMOVING, operations.publication.operation)

        assertTrue(operations.complete(lease))
        assertNull(operations.publication.operation)

        val nextLease = operations.begin(ProvisioningStorageOperation.IMPORTING)
        assertTrue(nextLease != null)
        assertTrue(operations.complete(nextLease))
    }

    @Test
    fun queuedCommandIsDiscardedExactlyOnce() {
        val operations = ProvisioningStorageOperations()
        val lease = checkNotNull(operations.begin(ProvisioningStorageOperation.REMOVING))

        var workRuns = 0
        var completionRuns = 0
        val command = ProvisioningStorageCommand(
            operations = operations,
            lease = lease,
            work = { workRuns++ },
            afterCompletion = { completionRuns++ },
        )

        command.discardIfQueued()
        command.discardIfQueued()
        command.run()

        assertEquals(1, completionRuns)
        assertEquals(0, workRuns)
        assertNull(operations.publication.operation)
    }

    @Test
    fun executedCommandReleasesLeaseAndRunsCompletion() {
        val operations = ProvisioningStorageOperations()
        val lease = checkNotNull(operations.begin(ProvisioningStorageOperation.IMPORTING))

        var workRuns = 0
        var completionRuns = 0
        val command = ProvisioningStorageCommand(
            operations = operations,
            lease = lease,
            work = { workRuns++ },
            afterCompletion = { completionRuns++ },
        )

        command.run()
        command.run()

        assertEquals(1, workRuns)
        assertEquals(1, completionRuns)
        assertNull(operations.publication.operation)
    }

    @Test
    fun observerReceivesCurrentAndStopsAfterUnsubscribe() {
        val operations = ProvisioningStorageOperations()
        val updates = mutableListOf<ProvisioningStorageOperationPublication>()
        val observation = operations.observeCurrent { update ->
            updates += update
        }

        assertEquals(1, updates.size)
        assertNull(updates[0].operation)
        assertEquals(0L, updates[0].revision)

        val lease = checkNotNull(operations.begin(ProvisioningStorageOperation.REMOVING))
        assertEquals(2, updates.size)
        assertEquals(ProvisioningStorageOperation.REMOVING, updates[1].operation)
        assertEquals(1L, updates[1].revision)

        observation.unsubscribe()
        operations.complete(lease)
        val leaseAfter = checkNotNull(operations.begin(ProvisioningStorageOperation.IMPORTING))
        operations.complete(leaseAfter)

        assertEquals(2, updates.size)
    }
}
