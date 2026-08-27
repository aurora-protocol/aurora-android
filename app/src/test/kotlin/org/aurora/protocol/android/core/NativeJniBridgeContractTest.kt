package org.aurora.protocol.android.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeJniBridgeContractTest {
    @Test
    fun exposesOnlyTheBoundedBinaryPacketIngressOperation() {
        val source = bridgeSource()

        assertTrue(source.contains("#define AURORA_INGRESS_LOCAL_PACKET_OPERATION 14"))
        assertFalse(source.contains("AURORA_INGRESS_LOCAL_PACKET_JSON_OPERATION"))
        assertEquals(4, Regex("\\bAURORA_INGRESS_LOCAL_PACKET_OPERATION\\b").findAll(source).count())
        assertTrue(source.contains("#define AURORA_MAX_LOCAL_PACKET_RESULT_BYTES (1024 * 1024)"))
        assertTrue(source.contains("maximum_output_bytes = 1 + AURORA_MAX_LOCAL_PACKET_RESULT_BYTES;"))
    }

    private fun bridgeSource(): String {
        val candidates = listOf(
            File("src/main/cpp/aurora_jni.c"),
            File("app/src/main/cpp/aurora_jni.c"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: throw IllegalStateException("JNI bridge source is unavailable")
    }
}
