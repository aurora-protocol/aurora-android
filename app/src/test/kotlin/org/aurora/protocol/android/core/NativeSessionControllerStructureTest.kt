package org.aurora.protocol.android.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSessionControllerStructureTest {
    @Test
    fun sessionControllerKeepsEstablishmentSeparateFromPacketIO() {
        val controller = controllerSource()
        val establish = establishSource()

        assertTrue(controller.contains("class NativeSessionController"))
        assertTrue(controller.contains("fun ingressLocalPacket(packet: ByteArray)"))
        assertTrue(controller.contains("fun nextLocalPacket()"))
        assertTrue(controller.contains("override fun close()"))
        assertTrue(establish.contains("fun NativeSessionController.establishNativeSession("))
        assertTrue(establish.contains("core.beginNativeSession(provisioning)"))
        assertTrue(establish.contains("issuer.exchange(work)"))
        assertFalse(controller.contains("core.beginNativeSession(provisioning)"))
        assertFalse(establish.contains("fun ingressLocalPacket(packet: ByteArray)"))
        assertFalse(establish.contains("fun nextLocalPacket()"))
    }

    private fun controllerSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/NativeSessionController.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/NativeSessionController.kt",
    )

    private fun establishSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/NativeSessionControllerEstablish.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/NativeSessionControllerEstablish.kt",
    )

    private fun readFirstExisting(vararg relativePaths: String): String =
        relativePaths
            .map(::File)
            .firstOrNull(File::isFile)
            ?.readText()
            ?: throw IllegalStateException("source file is unavailable")
}
