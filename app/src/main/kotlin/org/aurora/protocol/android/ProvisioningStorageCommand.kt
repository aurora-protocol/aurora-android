package org.aurora.protocol.android

import java.util.concurrent.atomic.AtomicBoolean

/** Ensures queued or running Activity work always releases its process lease exactly once. */
internal class ProvisioningStorageCommand(
    private val operations: ProvisioningStorageOperations,
    private val lease: ProvisioningStorageOperationLease,
    private val work: () -> Unit,
    private val afterCompletion: () -> Unit,
) : Runnable {
    private val claimed = AtomicBoolean()

    override fun run() {
        if (!claimed.compareAndSet(false, true)) {
            return
        }
        try {
            work()
        } finally {
            try {
                operations.complete(lease)
            } finally {
                afterCompletion()
            }
        }
    }

    fun discardIfQueued() {
        if (claimed.compareAndSet(false, true)) {
            try {
                operations.complete(lease)
            } finally {
                afterCompletion()
            }
        }
    }
}
