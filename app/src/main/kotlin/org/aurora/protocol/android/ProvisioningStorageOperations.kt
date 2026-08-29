package org.aurora.protocol.android

import java.util.concurrent.atomic.AtomicBoolean

internal enum class ProvisioningStorageOperation {
    IMPORTING,
    REMOVING,
}

internal data class ProvisioningStorageOperationPublication(
    val operation: ProvisioningStorageOperation?,
    val revision: Long,
)

internal data class ProvisioningStorageOperationLease internal constructor(
    val operation: ProvisioningStorageOperation,
    internal val id: Long,
)

internal data class ProvisioningStorageOperationObservation(
    val publication: ProvisioningStorageOperationPublication,
    val unsubscribe: () -> Unit,
)

/** Process-scoped exclusive ownership and observation for provisioning-store mutations. */
internal class ProvisioningStorageOperations {
    private val lock = Any()
    private val observers = mutableListOf<(ProvisioningStorageOperationPublication) -> Unit>()
    private var activeLease: ProvisioningStorageOperationLease? = null
    private var nextLeaseId = 0L
    private var revision = 0L

    val publication: ProvisioningStorageOperationPublication
        get() = synchronized(lock) {
            ProvisioningStorageOperationPublication(activeLease?.operation, revision)
        }

    fun begin(operation: ProvisioningStorageOperation): ProvisioningStorageOperationLease? {
        val transition = synchronized(lock) {
            if (activeLease != null) {
                null
            } else {
                val lease = ProvisioningStorageOperationLease(operation, ++nextLeaseId)
                activeLease = lease
                val publication = ProvisioningStorageOperationPublication(operation, ++revision)
                Triple(lease, publication, observers.toList())
            }
        } ?: return null
        notifyObservers(transition.second, transition.third)
        return transition.first
    }

    fun complete(lease: ProvisioningStorageOperationLease): Boolean {
        val transition = synchronized(lock) {
            if (activeLease != lease) {
                null
            } else {
                activeLease = null
                val publication = ProvisioningStorageOperationPublication(null, ++revision)
                publication to observers.toList()
            }
        } ?: return false
        notifyObservers(transition.first, transition.second)
        return true
    }

    fun observeCurrent(
        observer: (ProvisioningStorageOperationPublication) -> Unit,
    ): ProvisioningStorageOperationObservation {
        val initial = synchronized(lock) {
            observers += observer
            ProvisioningStorageOperationPublication(activeLease?.operation, revision)
        }
        return ProvisioningStorageOperationObservation(initial) {
            synchronized(lock) {
                observers.removeIf { it === observer }
            }
        }
    }

    private fun notifyObservers(
        publication: ProvisioningStorageOperationPublication,
        targets: List<(ProvisioningStorageOperationPublication) -> Unit>,
    ) {
        targets.forEach { observer ->
            try {
                observer(publication)
            } catch (_: Throwable) {
                // Operation ownership must not be broken by screen observation.
            }
        }
    }
}

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
