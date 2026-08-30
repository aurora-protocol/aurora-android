package org.aurora.protocol.android

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

internal fun Activity.buildMainScreenContent(
    initialStatusMessageId: Int,
    onImportTextChanged: () -> Unit,
    onImport: () -> Unit,
    onRemoveProvisioning: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
): Pair<View, MainScreenViews> {
    val contentPadding = mainScreenDp(24)
    val itemSpacing = mainScreenDp(12)
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    val importSection = buildMainScreenImportSection(onImportTextChanged)
    val importLabel = buildMainScreenImportLabel(importSection.importField.id)
    val statusSection = buildMainScreenStatusSection(initialStatusMessageId)
    var vpnCommandSection: MainScreenVpnCommandSection? = null
    var provisioningCommandSection: MainScreenProvisioningCommandSection? = null

    for (block in mainScreenContentBlocks()) {
        when (block) {
            MainScreenContentBlock.STATUS -> {
                layout.addView(statusSection.statusLabel, mainScreenMatchWidth())
                layout.addView(statusSection.status, mainScreenMatchWidth(mainScreenDp(4)))
                layout.addView(statusSection.progressIndicator, mainScreenWrapContent(itemSpacing))
            }
            MainScreenContentBlock.VPN_COMMANDS -> {
                vpnCommandSection = buildMainScreenVpnCommandSection(
                    layout = layout,
                    itemSpacing = itemSpacing,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                )
            }
            MainScreenContentBlock.IMPORT -> {
                layout.addView(importLabel, mainScreenMatchWidth(itemSpacing))
                layout.addView(importSection.importField, mainScreenMatchWidth(mainScreenDp(4)))
                layout.addView(importSection.importFieldError, mainScreenMatchWidth(mainScreenDp(4)))
                provisioningCommandSection = buildMainScreenProvisioningCommandSection(
                    layout = layout,
                    itemSpacing = itemSpacing,
                    onImport = onImport,
                    onRemoveProvisioning = onRemoveProvisioning,
                )
            }
        }
    }
    val vpnCommands = checkNotNull(vpnCommandSection)
    val provisioningCommands = checkNotNull(provisioningCommandSection)
    val commandSection = MainScreenCommandSection(
        importButton = provisioningCommands.importButton,
        removeProvisioningButton = provisioningCommands.removeProvisioningButton,
        connectButton = vpnCommands.connectButton,
        disconnectButton = vpnCommands.disconnectButton,
    )

    val root = ScrollView(this).apply {
        isFillViewport = true
        addView(
            layout,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        applyMainScreenSystemWindowInsets(this, contentPadding)
    }
    return root to MainScreenViews(
        importField = importSection.importField,
        importFieldError = importSection.importFieldError,
        defaultImportFieldTextColor = importSection.defaultImportFieldTextColor,
        importButton = commandSection.importButton,
        removeProvisioningButton = commandSection.removeProvisioningButton,
        connectButton = commandSection.connectButton,
        disconnectButton = commandSection.disconnectButton,
        progressIndicator = statusSection.progressIndicator,
        status = statusSection.status,
        statusLabelText = statusSection.statusLabelText,
    )
}
