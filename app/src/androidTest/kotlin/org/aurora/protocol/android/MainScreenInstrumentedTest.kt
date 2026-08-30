package org.aurora.protocol.android

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smoke coverage for the main screen layout and baseline accessibility tree. */
class MainScreenInstrumentedTest {
    @Test
    fun launchExposesProvisioningControlsAndGuardsEmptyImport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        launchActivity(instrumentation)

        val automation = instrumentation.uiAutomation
        val field = waitForNode(
            automation = automation,
            packageName = packageName,
            className = "android.widget.EditText",
            timeoutMs = 10_000,
        )
        assertNotNull("provisioning field should be present", field)

        val importButton = waitForNode(
            automation = automation,
            packageName = packageName,
            text = "Import provisioning code",
            timeoutMs = 5_000,
        )
        assertNotNull("import button should be present", importButton)
        assertFalse("empty import input should disable import", importButton!!.isEnabled)

        assertNotNull(
            "connect button should be present",
            waitForNode(
                automation = automation,
                packageName = packageName,
                text = "Connect",
                timeoutMs = 5_000,
            ),
        )
        assertNotNull(
            "disconnect button should be present",
            waitForNode(
                automation = automation,
                packageName = packageName,
                text = "Disconnect",
                timeoutMs = 5_000,
            ),
        )
        assertNotNull(
            "status label should be present",
            waitForNode(
                automation = automation,
                packageName = packageName,
                text = "Status",
                timeoutMs = 5_000,
            ),
        )
        val status = waitForNode(
            automation = automation,
            packageName = packageName,
            contentDescriptionPrefix = "Status: ",
            timeoutMs = 5_000,
        )
        assertNotNull("status should expose combined label and value", status)
        assertEquals("Status: ${status!!.text}", status.contentDescription?.toString())
    }

    @Test
    fun whitespaceOnlyInputKeepsImportDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        launchActivity(instrumentation)

        val automation = instrumentation.uiAutomation
        val field = waitForNode(
            automation = automation,
            packageName = packageName,
            className = "android.widget.EditText",
            timeoutMs = 10_000,
        )
        assertNotNull(field)
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, " \n\t ")
        assertTrue(field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))

        val importButton = waitForNode(
            automation = automation,
            packageName = packageName,
            text = "Import provisioning code",
            timeoutMs = 5_000,
        )
        assertNotNull(importButton)
        assertFalse(importButton!!.isEnabled)
    }

    @Test
    fun provisioningControlsExposeTalkBackHints() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        launchActivity(instrumentation)

        val automation = instrumentation.uiAutomation
        val field = waitForNode(
            automation = automation,
            packageName = packageName,
            className = "android.widget.EditText",
            timeoutMs = 10_000,
        )
        assertNotNull(field)
        assertEquals(
            context.getString(R.string.provisioning_import_field_hint),
            field!!.hintText?.toString(),
        )

        val importButton = waitForNode(
            automation = automation,
            packageName = packageName,
            text = "Import provisioning code",
            timeoutMs = 5_000,
        )
        assertNotNull(importButton)
        assertEquals(
            context.getString(R.string.action_import_hint),
            importButton!!.hintText?.toString(),
        )

        val connectButton = waitForNode(
            automation = automation,
            packageName = packageName,
            text = "Connect",
            timeoutMs = 5_000,
        )
        assertNotNull(connectButton)
        assertEquals(
            context.getString(R.string.action_connect_hint),
            connectButton!!.hintText?.toString(),
        )

        val disconnectButton = waitForNode(
            automation = automation,
            packageName = packageName,
            text = "Disconnect",
            timeoutMs = 5_000,
        )
        assertNotNull(disconnectButton)
        assertEquals(
            context.getString(R.string.action_disconnect_hint),
            disconnectButton!!.hintText?.toString(),
        )

        val removeProvisioningButton = waitForNode(
            automation = automation,
            packageName = packageName,
            text = "Remove stored provisioning",
            timeoutMs = 5_000,
        )
        assertNotNull(removeProvisioningButton)
        assertEquals(
            context.getString(R.string.action_remove_provisioning_hint),
            removeProvisioningButton!!.hintText?.toString(),
        )
    }

    @Test
    fun provisioningInputAndWindowExposeRuntimePrivacyGuards() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        val activity = launchActivity(instrumentation)

        var secureWindow = false
        instrumentation.runOnMainSync {
            secureWindow = activity.window.attributes.flags and
                WindowManager.LayoutParams.FLAG_SECURE != 0
        }
        assertTrue("main screen should prevent screenshots and recents capture", secureWindow)

        val field = waitForNode(
            automation = instrumentation.uiAutomation,
            packageName = packageName,
            className = "android.widget.EditText",
            timeoutMs = 10_000,
        )
        assertNotNull("provisioning field should be present", field)
        assertTrue("provisioning field should expose password semantics", field!!.isPassword)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertTrue(
                "provisioning field should be hidden from non-assistive accessibility services",
                field.isAccessibilityDataSensitive,
            )
        }
    }

    @Test
    fun invalidImportShowsInlineFieldError() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val packageName = context.packageName
        launchActivity(instrumentation)

        val automation = instrumentation.uiAutomation
        val field = waitForNode(
            automation = automation,
            packageName = packageName,
            className = "android.widget.EditText",
            timeoutMs = 10_000,
        )
        assertNotNull(field)
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "invalid-code")
        assertTrue(field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))

        val importButton = waitForNode(
            automation = automation,
            packageName = packageName,
            text = "Import provisioning code",
            timeoutMs = 5_000,
        )
        assertNotNull(importButton)
        assertTrue(importButton!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))

        val inlineError = waitForNode(
            automation = automation,
            packageName = packageName,
            text = context.getString(R.string.status_import_invalid),
            timeoutMs = 10_000,
        )
        assertNotNull("invalid import should show inline field feedback", inlineError)
    }

    private fun launchActivity(instrumentation: Instrumentation): AuroraActivity {
        instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, AuroraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AuroraActivity
        instrumentation.runOnMainSync {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        return activity
    }

    private fun waitForNode(
        automation: android.app.UiAutomation,
        packageName: String,
        text: String? = null,
        contentDescription: String? = null,
        contentDescriptionPrefix: String? = null,
        className: String? = null,
        timeoutMs: Long,
    ): AccessibilityNodeInfo? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            findNode(
                automation,
                packageName,
                text,
                contentDescription,
                contentDescriptionPrefix,
                className,
            )?.let { return it }
            instrumentation.waitForIdleSync()
        }
        return null
    }

    private fun findNode(
        automation: android.app.UiAutomation,
        packageName: String,
        text: String?,
        contentDescription: String?,
        contentDescriptionPrefix: String?,
        className: String?,
    ): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val textMatches = text == null || node.text?.toString() == text
            val contentDescriptionMatches = contentDescription == null ||
                node.contentDescription?.toString() == contentDescription
            val contentDescriptionPrefixMatches = contentDescriptionPrefix == null ||
                node.contentDescription?.toString()?.startsWith(contentDescriptionPrefix) == true
            val classMatches = className == null || node.className?.toString() == className
            if (
                node.packageName?.toString() == packageName &&
                textMatches &&
                contentDescriptionMatches &&
                contentDescriptionPrefixMatches &&
                classMatches
            ) {
                return node
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { queue.add(it) }
            }
        }
        return null
    }
}
