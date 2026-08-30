package org.aurora.protocol.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPackageSecurityStructureTest {
    @Test
    fun packageDisablesBackupsCleartextAndUnprotectedVpnBinding() {
        val manifest = source("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")

        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/full_backup_content\""))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(manifest.contains("android:permission=\"android.permission.BIND_VPN_SERVICE\""))
        assertFalse(manifest.contains("android:debuggable="))
    }

    @Test
    fun networkPolicyRejectsCleartextByDefault() {
        val policy = source(
            "src/main/res/xml/network_security_config.xml",
            "app/src/main/res/xml/network_security_config.xml",
        )

        assertTrue(policy.contains("<base-config cleartextTrafficPermitted=\"false\""))
        assertFalse(policy.contains("<certificates src=\"user\""))
        assertFalse(policy.contains("cleartextTrafficPermitted=\"true\""))
        assertFalse(policy.contains("<domain-config"))
        assertFalse(policy.contains("<debug-overrides"))
        assertFalse(policy.contains("<pin-set"))
    }

    @Test
    fun backupPoliciesExcludeEverySupportedStorageDomain() {
        val extraction = source(
            "src/main/res/xml/data_extraction_rules.xml",
            "app/src/main/res/xml/data_extraction_rules.xml",
        )
        val legacy = source(
            "src/main/res/xml/full_backup_content.xml",
            "app/src/main/res/xml/full_backup_content.xml",
        )
        val credentialDomains = listOf("root", "file", "database", "sharedpref", "external")
        val deviceDomains = listOf("device_root", "device_file", "device_database", "device_sharedpref")

        (credentialDomains + deviceDomains).forEach { domain ->
            val exclusion = "<exclude domain=\"$domain\" path=\".\" />"
            assertTrue(extraction.contains(exclusion))
            assertTrue(legacy.contains(exclusion))
        }
        assertTrue(extraction.contains("<cloud-backup>"))
        assertTrue(extraction.contains("<device-transfer>"))
        credentialDomains.forEach { domain ->
            assertTrue(extraction.countOccurrences("<exclude domain=\"$domain\" path=\".\" />") == 2)
        }
        deviceDomains.forEach { domain ->
            assertTrue(extraction.countOccurrences("<exclude domain=\"$domain\" path=\".\" />") == 2)
        }
    }

    @Test
    fun releaseBuildStaysOptimizedAndNativeTrustGated() {
        val build = source("build.gradle.kts", "app/build.gradle.kts", "../build.gradle.kts", "../app/build.gradle.kts")
        val trust = source("scripts/verify-release-native-trust.sh", "../scripts/verify-release-native-trust.sh")
        val nativeBuild = source("scripts/build-native-core.sh", "../scripts/build-native-core.sh")

        assertTrue(build.contains("isDebuggable = false"))
        assertTrue(build.contains("isJniDebuggable = false"))
        assertTrue(build.contains("isMinifyEnabled = true"))
        assertTrue(build.contains("isShrinkResources = true"))
        assertTrue(build.contains("proguard-android-optimize.txt"))
        assertTrue(build.contains("if (name == \"preReleaseBuild\")"))
        assertTrue(build.contains("dependsOn(verifyReleaseNativeTrust)"))

        assertTrue(trust.contains("set -eu"))
        assertTrue(trust.contains("AURORA_RELEASE_TRUST_SHA256"))
        assertTrue(trust.contains("--untracked-files=all"))
        assertTrue(trust.contains("check-native-provisioning-trust"))
        assertTrue(trust.contains("actual_trust_sha256"))

        assertTrue(nativeBuild.contains("-buildvcs=true"))
        assertTrue(nativeBuild.contains("vcs.modified=false"))
        assertTrue(nativeBuild.contains("AuroraCoreZeroFree"))
        assertTrue(nativeBuild.contains("0x4000"))
    }

    private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }

    private fun source(vararg relativePaths: String): String = relativePaths
        .map(::File)
        .firstOrNull(File::isFile)
        ?.readText()
        ?: throw IllegalStateException("source file is unavailable")
}
