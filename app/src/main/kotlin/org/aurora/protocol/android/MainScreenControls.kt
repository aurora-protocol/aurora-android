package org.aurora.protocol.android

internal data class MainScreenControls(
    val importInputEnabled: Boolean,
    val importEnabled: Boolean,
    val connectEnabled: Boolean,
    val showProgress: Boolean,
)

internal fun mainScreenControls(
    importInProgress: Boolean,
    connectRequested: Boolean,
    hasProvisioningInput: Boolean,
): MainScreenControls {
    val busy = importInProgress || connectRequested
    return MainScreenControls(
        importInputEnabled = !busy,
        importEnabled = !busy && hasProvisioningInput,
        connectEnabled = !busy,
        showProgress = busy,
    )
}
