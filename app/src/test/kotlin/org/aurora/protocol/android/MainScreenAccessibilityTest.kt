package org.aurora.protocol.android

import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenAccessibilityTest {
    @Test
    fun statusContentDescriptionCombinesLabelAndValue() {
        assertEquals(
            "Status: VPN connected",
            statusContentDescription("Status", "VPN connected"),
        )
    }

    @Test
    fun statusContentDescriptionPreservesEmptyValue() {
        assertEquals(
            "Status: ",
            statusContentDescription("Status", ""),
        )
    }

    @Test
    fun statusContentDescriptionUsesColonSeparator() {
        assertEquals(
            "Provisioning: Importing",
            statusContentDescription("Provisioning", "Importing"),
        )
    }
}
