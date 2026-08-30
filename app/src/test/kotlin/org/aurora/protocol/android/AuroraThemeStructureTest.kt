package org.aurora.protocol.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraThemeStructureTest {
    @Test
    fun applicationUsesExplicitLightAndDarkPlatformThemes() {
        val manifest = source("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
        val light = source("src/main/res/values/themes.xml", "app/src/main/res/values/themes.xml")
        val dark = source("src/main/res/values-night/themes.xml", "app/src/main/res/values-night/themes.xml")
        val lightV27 = source("src/main/res/values-v27/themes.xml", "app/src/main/res/values-v27/themes.xml")
        val darkV27 = source("src/main/res/values-night-v27/themes.xml", "app/src/main/res/values-night-v27/themes.xml")

        assertTrue(manifest.contains("android:theme=\"@style/Theme.Aurora\""))
        assertTrue(light.contains("parent=\"@android:style/Theme.Material.Light\""))
        assertTrue(light.contains("android:windowLightStatusBar\">true"))
        assertTrue(lightV27.contains("android:windowLightNavigationBar\">true"))
        assertTrue(dark.contains("parent=\"@android:style/Theme.Material\""))
        assertTrue(dark.contains("android:windowLightStatusBar\">false"))
        assertTrue(darkV27.contains("android:windowLightNavigationBar\">false"))
    }

    @Test
    fun dayAndNightThemesShareNamedAdaptiveAccentResources() {
        val lightTheme = source("src/main/res/values/themes.xml", "app/src/main/res/values/themes.xml")
        val darkTheme = source("src/main/res/values-night/themes.xml", "app/src/main/res/values-night/themes.xml")
        val lightColor = source("src/main/res/values/colors.xml", "app/src/main/res/values/colors.xml")
        val darkColor = source("src/main/res/values-night/colors.xml", "app/src/main/res/values-night/colors.xml")

        assertTrue(lightTheme.contains("@color/aurora_accent"))
        assertTrue(darkTheme.contains("@color/aurora_accent"))
        assertTrue(lightColor.contains("#0079FF"))
        assertTrue(darkColor.contains("#0A84FF"))
    }

    private fun source(vararg paths: String): String =
        paths.map(::File).first(File::isFile).readText()
}
