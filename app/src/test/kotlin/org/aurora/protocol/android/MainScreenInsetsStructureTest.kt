package org.aurora.protocol.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenInsetsStructureTest {
    @Test
    fun edgeToEdgePaddingIncludesBarsCutoutsAndKeyboard() {
        val source = source("MainScreenLayoutMetrics.kt")

        assertTrue(
            source.contains(
                "WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()",
            ),
        )
        assertTrue(source.contains("val keyboard = insets.getInsets(WindowInsets.Type.ime())"))
        assertTrue(source.contains("bottom = maxOf(safeDrawing.bottom, keyboard.bottom)"))
        assertTrue(source.contains("contentPadding + left"))
        assertTrue(source.contains("contentPadding + bottom"))
    }

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/org/aurora/protocol/android/$name"),
        File("app/src/main/kotlin/org/aurora/protocol/android/$name"),
    ).first(File::isFile).readText()
}
