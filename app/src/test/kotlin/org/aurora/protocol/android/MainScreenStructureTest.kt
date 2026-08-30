package org.aurora.protocol.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenStructureTest {
    @Test
    fun activityExposesAccessibilityMetadata() {
        val layout = layoutSource()
        val metrics = metricsSource()
        val accessibility = accessibilitySource()

        assertFalse(layout.contains("contentDescription = getString(R.string.provisioning_import_label)"))
        assertTrue(layout.contains("applyAccessibilityHint(this, getString(R.string.provisioning_import_field_hint))"))
        assertTrue(layout.contains("accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE"))
        assertFalse(layout.contains("contentDescription = getString(R.string.provisioning_import_field_error)"))
        assertTrue(layout.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO"))
        assertTrue(metrics.contains("minHeight = mainScreenDp(48)"))
        assertTrue(metrics.contains("fun Activity.mainScreenThemeColor("))
        assertTrue(layout.contains("mainScreenThemeColor(android.R.attr.colorError)"))
        assertFalse(layout.contains("0xFFB00020"))
        assertTrue(layout.contains("mainScreenCommandButton("))
        assertTrue(layout.contains("labelFor = importFieldId"))
        assertTrue(layout.contains("labelFor = status.id"))
        assertTrue(layout.contains("isAccessibilityHeading = true"))
        assertTrue(layout.contains("applyStatusPresentation("))
        assertTrue(accessibility.contains("fun applyAccessibilityHint("))
    }

    @Test
    fun accessibilityHelpersStaySeparateFromActivityLayout() {
        val helpers = accessibilitySource()

        assertTrue(helpers.contains("fun applyAccessibilityHint("))
        assertTrue(helpers.contains("info.hintText = hint"))
        assertTrue(helpers.contains("fun statusContentDescription("))
        assertTrue(helpers.contains("fun applyStatusPresentation("))
        assertFalse(helpers.contains("class AuroraActivity"))
    }

    @Test
    fun activityWiresCommandButtonsThroughAccessibilityHints() {
        val lifecycle = lifecycleSource()
        val layout = layoutSource()
        val strings = stringsSource()

        assertTrue(lifecycle.contains("onImport = ::importProvisioning"))
        assertTrue(lifecycle.contains("onConnect = ::connect"))
        assertTrue(layout.contains("mainScreenCommandButton(R.string.action_import, R.string.action_import_hint"))
        assertTrue(layout.contains("mainScreenCommandButton(R.string.action_connect, R.string.action_connect_hint"))
        assertTrue(layout.contains("R.string.action_disconnect"))
        assertTrue(layout.contains("R.string.action_disconnect_hint"))
        assertTrue(layout.contains("R.string.action_remove_provisioning"))
        assertTrue(strings.contains("name=\"action_import_hint\""))
        assertTrue(strings.contains("name=\"action_connect_hint\""))
        assertTrue(strings.contains("name=\"action_disconnect_hint\""))
        assertTrue(strings.contains("name=\"action_remove_provisioning_hint\""))
        assertTrue(strings.contains("name=\"action_cancel\""))
        assertTrue(strings.contains("name=\"action_cancel_hint\""))
        assertTrue(strings.contains("name=\"action_retry\""))
        assertTrue(strings.contains("name=\"action_retry_hint\""))
    }

    @Test
    fun controlsLogicStaysSeparateFromActivityLayout() {
        val controls = controlsSource()
        val controlsCore = controlsCoreSource()
        val controlsCopy = controlsCopySource()
        val controlsTypes = controlsTypesSource()

        assertTrue(controls.contains("fun mainScreenControls("))
        assertTrue(controlsCopy.contains("fun mainScreenActionCopies("))
        assertTrue(controlsCopy.contains("fun shouldShowImportFieldError("))
        assertTrue(controlsCopy.contains("fun mainScreenActionCopyResource("))
        assertTrue(controlsCopy.contains("fun mainScreenActionHintResource("))
        assertTrue(controlsCopy.contains("fun isDisconnectCancelingConnection("))
        assertTrue(controlsTypes.contains("data class MainScreenControls"))
        assertFalse(controlsCore.contains("fun mainScreenActionCopies("))
        assertFalse(controlsCopy.contains("fun mainScreenControls("))
        assertFalse(controlsTypes.contains("fun mainScreenControls("))
        assertFalse(controls.contains("class AuroraActivity"))
    }

    @Test
    fun activityKeepsLifecycleSeparateFromLayoutAndCommands() {
        val activity = activitySource()
        val layout = layoutSource()
        val provisioning = provisioningSource()
        val vpn = vpnSource()
        val rendering = renderingSource()
        val statusMessages = statusMessagesSource()

        val lifecycle = lifecycleSource()
        val lifecycleInitialize = lifecycleInitializeSource()
        val lifecycleRestore = lifecycleRestoreSource()
        val lifecycleSaveState = lifecycleSaveStateSource()
        val lifecycleObservers = lifecycleObserversSource()
        val permissionCallbacks = permissionCallbacksSource()
        val types = typesSource()

        assertTrue(activity.contains("class AuroraActivity : Activity()"))
        assertTrue(activity.contains("override fun onCreate("))
        assertTrue(activity.contains("override fun onResume("))
        assertTrue(activity.contains("initializeMainScreen(savedInstanceState)"))
        assertTrue(lifecycleInitializeSource().contains("fun AuroraActivity.initializeMainScreen("))
        assertTrue(lifecycleRestore.contains("fun AuroraActivity.restoreMainScreenLifecycle("))
        assertTrue(lifecycleSaveState.contains("fun AuroraActivity.saveActivityState("))
        assertTrue(lifecycleObservers.contains("fun AuroraActivity.resumeMainScreenObservers()"))
        assertTrue(lifecycleObservers.contains("fun AuroraActivity.destroyMainScreenResources()"))
        assertFalse(lifecycleInitializeSource().contains("fun AuroraActivity.resumeMainScreenObservers()"))
        assertFalse(lifecycleInitializeSource().contains("fun AuroraActivity.saveActivityState("))
        assertFalse(lifecycleRestore.contains("fun Activity.buildMainScreenContent("))
        assertTrue(permissionCallbacks.contains("fun AuroraActivity.handleVpnPermissionResult("))
        assertFalse(activity.contains("fun AuroraActivity.resumeMainScreenObservers()"))
        assertFalse(lifecycle.contains("override fun onCreate("))
        assertTrue(layout.contains("fun Activity.buildMainScreenContent("))
        assertTrue(types.contains("data class MainScreenViews"))
        assertTrue(provisioning.contains("fun AuroraActivity.importProvisioning()"))
        assertTrue(provisioning.contains("fun AuroraActivity.removeProvisioning()"))
        assertTrue(vpn.contains("fun AuroraActivity.connect()"))
        assertTrue(rendering.contains("fun AuroraActivity.refreshControls()"))
        assertTrue(rendering.contains("fun AuroraActivity.renderTunnelStatus("))
        assertTrue(statusMessages.contains("fun mainScreenInitialStatusMessage("))
        assertFalse(activity.contains("fun Activity.buildMainScreenContent("))
        assertFalse(activity.contains("fun AuroraActivity.importProvisioning()"))
        assertFalse(layout.contains("override fun onCreate("))
    }

    @Test
    fun layoutMetricsStaySeparateFromMainScreenComposition() {
        val layout = layoutSource()
        val metrics = metricsSource()
        val types = typesSource()

        assertTrue(types.contains("data class MainScreenViews"))
        assertTrue(metrics.contains("fun Activity.mainScreenCommandButton("))
        assertTrue(metrics.contains("fun Activity.mainScreenDp("))
        assertTrue(metrics.contains("fun Activity.applyMainScreenSystemWindowInsets("))
        assertTrue(layout.contains("fun Activity.buildMainScreenContent("))
        assertFalse(layout.contains("data class MainScreenViews"))
        assertFalse(layout.contains("fun Activity.mainScreenCommandButton("))
        assertFalse(metrics.contains("fun Activity.buildMainScreenContent("))
    }

    private fun stringsSource(): String = readFirstExisting(
        "src/main/res/values/strings.xml",
        "app/src/main/res/values/strings.xml",
    )

    private fun controlsSource(): String = listOf(
        controlsCoreSource(),
        controlsCopySource(),
        controlsTypesSource(),
    ).joinToString("\n")

    private fun controlsCoreSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/MainScreenControls.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/MainScreenControls.kt",
    )

    private fun controlsCopySource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/MainScreenControlsCopy.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/MainScreenControlsCopy.kt",
    )

    private fun controlsTypesSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/MainScreenControlsTypes.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/MainScreenControlsTypes.kt",
    )

    private fun lifecycleSource(): String = listOf(
        lifecycleInitializeSource(),
        lifecycleRestoreSource(),
        lifecycleSaveStateSource(),
        lifecycleObserversSource(),
    ).joinToString("\n")

    private fun lifecycleRestoreSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivityLifecycleRestore.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivityLifecycleRestore.kt",
    )

    private fun lifecycleSaveStateSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivityLifecycleSaveState.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivityLifecycleSaveState.kt",
    )

    private fun lifecycleInitializeSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivityLifecycleInitialize.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivityLifecycleInitialize.kt",
    )

    private fun lifecycleObserversSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivityLifecycleObservers.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivityLifecycleObservers.kt",
    )

    private fun permissionCallbacksSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivityPermissionCallbacks.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivityPermissionCallbacks.kt",
    )

    private fun activitySource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivity.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivity.kt",
    )

    private fun accessibilitySource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/MainScreenAccessibility.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/MainScreenAccessibility.kt",
    )

    @Test
    fun layoutStatusSectionStaysSeparateFromMainScreenComposition() {
        val layout = layoutCoreSource()
        val statusSection = layoutStatusSectionSource()

        assertTrue(layout.contains("buildMainScreenStatusSection("))
        assertTrue(layout.contains("for (block in mainScreenContentBlocks())"))
        assertTrue(statusSection.contains("fun Activity.buildMainScreenStatusSection("))
        assertTrue(statusSection.contains("applyStatusPresentation(status, statusLabelText"))
        assertTrue(statusSection.contains("importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO"))
        assertFalse(layout.contains("applyStatusPresentation(status, statusLabelText"))
        assertFalse(statusSection.contains("fun Activity.buildMainScreenContent("))
    }

    @Test
    fun layoutImportSectionStaysSeparateFromCommandSection() {
        val layout = layoutSource()
        val importSection = layoutImportSectionSource()
        val commandSection = layoutCommandSectionSource()

        assertTrue(layout.contains("fun Activity.buildMainScreenContent("))
        assertTrue(importSection.contains("fun Activity.buildMainScreenImportSection("))
        assertTrue(commandSection.contains("fun Activity.buildMainScreenVpnCommandSection("))
        assertTrue(commandSection.contains("fun Activity.buildMainScreenProvisioningCommandSection("))
        assertFalse(commandSection.contains("fun Activity.buildMainScreenCommandSection("))
        assertTrue(importSection.contains("ProvisioningImport.maximumEncodedCharacters"))
        assertTrue(commandSection.contains("mainScreenCommandButton(R.string.action_import"))
        assertTrue(commandSection.contains("mainScreenCommandButton(R.string.action_connect"))
        assertFalse(importSection.contains("mainScreenCommandButton(R.string.action_connect"))
        assertFalse(commandSection.contains("InputFilter.LengthFilter"))
    }

    private fun layoutSource(): String = listOf(
        layoutCoreSource(),
        layoutImportSectionSource(),
        layoutCommandSectionSource(),
        layoutStatusSectionSource(),
    ).joinToString("\n")

    private fun layoutCoreSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/MainScreenLayout.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/MainScreenLayout.kt",
    )

    private fun layoutImportSectionSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/MainScreenLayoutImportSection.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/MainScreenLayoutImportSection.kt",
    )

    private fun layoutStatusSectionSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/MainScreenLayoutStatusSection.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/MainScreenLayoutStatusSection.kt",
    )

    private fun layoutCommandSectionSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/MainScreenLayoutCommandSection.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/MainScreenLayoutCommandSection.kt",
    )

    @Test
    fun provisioningImportKeepsBackgroundWorkSeparateFromImportOrchestration() {
        val importOrchestration = provisioningImportSource()
        val importWork = provisioningImportWorkSource()

        assertTrue(importOrchestration.contains("fun AuroraActivity.importProvisioning()"))
        assertTrue(importWork.contains("fun AuroraActivity.buildProvisioningImportCommand("))
        assertTrue(importWork.contains("ProvisioningImport.decode(encoded)"))
        assertFalse(importOrchestration.contains("ProvisioningImport.decode(encoded)"))
        assertFalse(importWork.contains("RejectedExecutionException"))
    }

    @Test
    fun provisioningImportFieldExcludesSensitiveTextServices() {
        val importSection = layoutImportSectionSource()

        assertTrue(importSection.contains("isSaveEnabled = false"))
        assertTrue(importSection.contains("InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS"))
        assertTrue(importSection.contains("EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING"))
        assertTrue(importSection.contains("View.IMPORTANT_FOR_AUTOFILL_NO"))
        assertTrue(importSection.contains("View.IMPORTANT_FOR_CONTENT_CAPTURE_NO"))
        assertTrue(importSection.contains("Build.VERSION_CODES.UPSIDE_DOWN_CAKE"))
        assertTrue(importSection.contains("setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)"))
        assertTrue(importSection.contains("InputFilter.LengthFilter(ProvisioningImport.maximumEncodedCharacters)"))
    }

    @Test
    fun provisioningImportKeepsFieldErrorsSeparateFromImportWork() {
        val importWork = provisioningImportSource()
        val importErrors = provisioningImportErrorsSource()

        assertTrue(importWork.contains("fun AuroraActivity.importProvisioning()"))
        assertTrue(importErrors.contains("fun AuroraActivity.showImportFailure("))
        assertTrue(importErrors.contains("fun AuroraActivity.clearImportFieldError()"))
        assertTrue(importErrors.contains("importFieldError.currentTextColor"))
        assertFalse(importErrors.contains("0xFFB00020"))
        assertFalse(importWork.contains("fun AuroraActivity.showImportFieldError("))
        assertFalse(importErrors.contains("ProvisioningStorageCommand("))
    }

    private fun provisioningSource(): String = listOf(
        provisioningImportSource(),
        provisioningImportWorkSource(),
        provisioningRemovalSource(),
        provisioningImportErrorsSource(),
    ).joinToString("\n")

    private fun provisioningImportWorkSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivityProvisioningImportWork.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivityProvisioningImportWork.kt",
    )

    private fun provisioningImportSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivityProvisioningImport.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivityProvisioningImport.kt",
    )

    private fun provisioningImportErrorsSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivityProvisioningImportErrors.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivityProvisioningImportErrors.kt",
    )

    private fun provisioningRemovalSource(): String = listOf(
        provisioningRemovalConfirmSource(),
        provisioningRemovalWorkSource(),
    ).joinToString("\n")

    private fun provisioningRemovalConfirmSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivityProvisioningRemovalConfirm.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivityProvisioningRemovalConfirm.kt",
    )

    private fun provisioningRemovalWorkSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivityProvisioningRemovalWork.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivityProvisioningRemovalWork.kt",
    )

    @Test
    fun provisioningRemovalKeepsConfirmationSeparateFromBackgroundWork() {
        val confirm = provisioningRemovalConfirmSource()
        val work = provisioningRemovalWorkSource()

        assertTrue(confirm.contains("fun AuroraActivity.confirmRemoveProvisioning()"))
        assertTrue(work.contains("fun AuroraActivity.removeProvisioning()"))
        assertTrue(work.contains("ProvisioningStorageCommand("))
        assertFalse(confirm.contains("ProvisioningStorageCommand("))
        assertFalse(work.contains("AlertDialog.Builder"))
    }

    private fun metricsSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/MainScreenLayoutMetrics.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/MainScreenLayoutMetrics.kt",
    )

    private fun typesSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/MainScreenLayoutTypes.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/MainScreenLayoutTypes.kt",
    )

    private fun vpnSource(): String = listOf(
        vpnConnectSource(),
        vpnDisconnectSource(),
        vpnReconciliationSource(),
    ).joinToString("\n")

    private fun vpnConnectSource(): String = listOf(
        vpnConnectPrepareSource(),
        vpnConnectStartSource(),
    ).joinToString("\n")

    private fun vpnConnectPrepareSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivityVpnConnectPrepare.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivityVpnConnectPrepare.kt",
    )

    private fun vpnConnectStartSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivityVpnConnectStart.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivityVpnConnectStart.kt",
    )

    private fun vpnDisconnectSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivityVpnDisconnect.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivityVpnDisconnect.kt",
    )

    private fun vpnReconciliationSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivityVpnReconciliation.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivityVpnReconciliation.kt",
    )

    @Test
    fun vpnConnectKeepsPreparationSeparateFromServiceStart() {
        val prepare = vpnConnectPrepareSource()
        val start = vpnConnectStartSource()

        assertTrue(prepare.contains("fun AuroraActivity.connect()"))
        assertTrue(prepare.contains("fun AuroraActivity.requestVpnPreparation()"))
        assertTrue(start.contains("fun AuroraActivity.startConnection()"))
        assertTrue(prepare.contains("VpnService.prepare(this)"))
        assertTrue(start.contains("runVpnServiceRequest"))
        assertFalse(prepare.contains("runVpnServiceRequest"))
        assertFalse(start.contains("VpnService.prepare(this)"))
    }

    @Test
    fun vpnLifecycleStaysSeparateFromConnectDisconnectAndReconciliation() {
        val connect = vpnConnectSource()
        val connectPrepare = vpnConnectPrepareSource()
        val connectStart = vpnConnectStartSource()
        val disconnect = vpnDisconnectSource()
        val reconciliation = vpnReconciliationSource()

        assertTrue(connect.contains("fun AuroraActivity.connect()"))
        assertTrue(connect.contains("fun AuroraActivity.startConnection()"))
        assertTrue(connectPrepare.contains("fun AuroraActivity.failConnectionRequest("))
        assertTrue(connectStart.contains("vpnConnectRequestGate.issue()"))
        assertTrue(disconnect.contains("fun AuroraActivity.disconnect()"))
        assertTrue(reconciliation.contains("fun AuroraActivity.reconcilePendingVpnServiceRequest()"))
        assertTrue(reconciliation.contains("fun AuroraActivity.schedulePendingVpnServiceReconciliation()"))
        assertFalse(connect.contains("fun AuroraActivity.disconnect()"))
        assertFalse(disconnect.contains("fun AuroraActivity.reconcilePendingVpnServiceRequest()"))
    }

    @Test
    fun activityRenderingKeepsStatusPresentationSeparateFromControls() {
        val status = renderingStatusSource()
        val controls = renderingControlsSource()

        assertTrue(status.contains("fun AuroraActivity.showLocalStatus("))
        assertTrue(status.contains("fun AuroraActivity.renderTunnelStatus("))
        assertTrue(controls.contains("fun AuroraActivity.refreshControls()"))
        assertTrue(controls.contains("fun AuroraActivity.currentControls()"))
        assertTrue(controls.contains("applyAccessibilityHint(connectButton, getString(mainScreenActionHintResource("))
        assertTrue(controls.contains("mainScreenActionHintResource(actionCopies.disconnectAction)"))
        assertFalse(status.contains("fun AuroraActivity.refreshControls()"))
        assertFalse(controls.contains("fun AuroraActivity.renderTunnelStatus("))
    }

    private fun renderingSource(): String = listOf(
        renderingStatusSource(),
        renderingControlsSource(),
    ).joinToString("\n")

    private fun renderingStatusSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivityRenderingStatus.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivityRenderingStatus.kt",
    )

    private fun renderingControlsSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/AuroraActivityRenderingControls.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/AuroraActivityRenderingControls.kt",
    )

    private fun statusMessagesSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/MainScreenStatusMessages.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/MainScreenStatusMessages.kt",
    )

    private fun readFirstExisting(vararg relativePaths: String): String =
        relativePaths
            .map(::File)
            .firstOrNull(File::isFile)
            ?.readText()
            ?: throw IllegalStateException("source file is unavailable")
}
