package org.aurora.protocol.android

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout

internal data class MainScreenCommandSection(
    val importButton: Button,
    val removeProvisioningButton: Button,
    val connectButton: Button,
    val disconnectButton: Button,
)

internal data class MainScreenVpnCommandSection(
    val connectButton: Button,
    val disconnectButton: Button,
)

internal data class MainScreenProvisioningCommandSection(
    val importButton: Button,
    val removeProvisioningButton: Button,
)

internal fun Activity.buildMainScreenVpnCommandSection(
    layout: LinearLayout,
    itemSpacing: Int,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
): MainScreenVpnCommandSection {
    val connectButton = mainScreenCommandButton(R.string.action_connect, R.string.action_connect_hint, onConnect)
    val disconnectButton = mainScreenCommandButton(
        R.string.action_disconnect,
        R.string.action_disconnect_hint,
        onDisconnect,
    )
    layout.addView(connectButton, mainScreenMatchWidth(itemSpacing))
    layout.addView(disconnectButton, mainScreenMatchWidth(itemSpacing))
    return MainScreenVpnCommandSection(
        connectButton = connectButton,
        disconnectButton = disconnectButton,
    )
}

internal fun Activity.buildMainScreenProvisioningCommandSection(
    layout: LinearLayout,
    itemSpacing: Int,
    onImport: () -> Unit,
    onRemoveProvisioning: () -> Unit,
): MainScreenProvisioningCommandSection {
    val importButton = mainScreenCommandButton(R.string.action_import, R.string.action_import_hint, onImport)
    val removeProvisioningButton = mainScreenCommandButton(
        R.string.action_remove_provisioning,
        R.string.action_remove_provisioning_hint,
        onRemoveProvisioning,
    )
    layout.addView(importButton, mainScreenMatchWidth(itemSpacing))
    layout.addView(removeProvisioningButton, mainScreenMatchWidth(itemSpacing))
    return MainScreenProvisioningCommandSection(
        importButton = importButton,
        removeProvisioningButton = removeProvisioningButton,
    )
}
