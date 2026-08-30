package org.aurora.protocol.android

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
            } catch (_: Exception) {
                // Operation ownership must not be broken by screen observation.
            }
        }
    }
}
