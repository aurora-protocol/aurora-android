package org.aurora.protocol.android

internal enum class ProvisioningStorageOperation {
    IMPORTING,
    REMOVING,
}

internal data class ProvisioningStorageOperationPublication(
    val operation: ProvisioningStorageOperation?,
    val revision: Long,
)

internal data class ProvisioningStorageOperationLease(
    val operation: ProvisioningStorageOperation,
    internal val id: Long,
)

internal data class ProvisioningStorageOperationObservation(
    val publication: ProvisioningStorageOperationPublication,
    val unsubscribe: () -> Unit,
)
