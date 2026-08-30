package org.aurora.protocol.android

import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenLayoutOrderTest {
    @Test
    fun contentPutsStatusAndVpnCommandsBeforeImport() {
        assertEquals(
            listOf(
                MainScreenContentBlock.STATUS,
                MainScreenContentBlock.VPN_COMMANDS,
                MainScreenContentBlock.IMPORT,
            ),
            mainScreenContentBlocks(),
        )
    }
}
