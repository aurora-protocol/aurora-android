package org.aurora.protocol.android

import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenControlsTest {
    @Test
    fun importRequiresNonEmptyInput() {
        assertEquals(
            MainScreenControls(
                importInputEnabled = true,
                importEnabled = false,
                connectEnabled = true,
                showProgress = false,
            ),
            mainScreenControls(
                importInProgress = false,
                connectRequested = false,
                hasProvisioningInput = false,
            ),
        )
    }

    @Test
    fun idleScreenEnablesImportWhenInputIsPresent() {
        assertEquals(
            MainScreenControls(
                importInputEnabled = true,
                importEnabled = true,
                connectEnabled = true,
                showProgress = false,
            ),
            mainScreenControls(
                importInProgress = false,
                connectRequested = false,
                hasProvisioningInput = true,
            ),
        )
    }

    @Test
    fun importAndConnectionRequestsExposeBusyControls() {
        val expected = MainScreenControls(
            importInputEnabled = false,
            importEnabled = false,
            connectEnabled = false,
            showProgress = true,
        )

        assertEquals(expected, mainScreenControls(true, false, true))
        assertEquals(expected, mainScreenControls(false, true, true))
    }
}
