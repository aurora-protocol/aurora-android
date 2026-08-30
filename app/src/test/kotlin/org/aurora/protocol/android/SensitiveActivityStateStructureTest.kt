package org.aurora.protocol.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SensitiveActivityStateStructureTest {
    @Test
    fun savedActivityStringsAreLimitedToLifecycleCoordinationMetadata() {
        val source = source("AuroraActivityLifecycleSaveState.kt")
        val persistedStrings = source
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("outState.putString(") }
            .toList()

        assertEquals(
            listOf(
                "outState.putString(AuroraActivity.savedConnectionRequestProcessSession, vpnServiceProcessSessionId)",
                "outState.putString(AuroraActivity.savedVpnServiceCommand, pendingCommand.command.name)",
                "outState.putString(AuroraActivity.savedVpnServiceProcessSession, vpnServiceProcessSessionId)",
            ),
            persistedStrings,
        )
        assertFalse(source.contains("importField"))
        assertFalse(source.contains("putByteArray("))
        assertFalse(source.contains("putCharSequence("))
    }

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/org/aurora/protocol/android/$name"),
        File("app/src/main/kotlin/org/aurora/protocol/android/$name"),
    ).first(File::isFile).readText()
}
