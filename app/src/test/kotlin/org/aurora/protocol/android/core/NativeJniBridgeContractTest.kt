package org.aurora.protocol.android.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeJniBridgeContractTest {
    @Test
    fun exposesOnlyTheBoundedBinaryProvisioningReservationOperation() {
        val source = bridgeSource()

        assertTrue(source.contains("#define AURORA_RESERVATION_OPERATION 19"))
        assertFalse(source.contains("AURORA_RESERVATION_JSON_OPERATION"))
        assertEquals(4, Regex("\\bAURORA_RESERVATION_OPERATION\\b").findAll(source).count())
        assertTrue(source.contains("#define AURORA_MAX_NATIVE_PROVISIONING_BYTES (1024 * 1024)"))
        assertTrue(source.contains("#define AURORA_RESERVATION_RESULT_METADATA_BYTES (3 + 48 + 16 + 8)"))
        assertTrue(
            source.contains(
                "(AURORA_MAX_NATIVE_PROVISIONING_BYTES + AURORA_RESERVATION_RESULT_METADATA_BYTES)",
            ),
        )
        assertTrue(source.contains("maximum_input_bytes = AURORA_MAX_NATIVE_PROVISIONING_BYTES;"))
        assertTrue(source.contains("maximum_output_bytes = 1 + AURORA_MAX_RESERVATION_RESULT_BYTES;"))
    }

    @Test
    fun exposesOnlyTheBoundedBinaryPacketIngressOperation() {
        val source = bridgeSource()

        assertTrue(source.contains("#define AURORA_INGRESS_LOCAL_PACKET_OPERATION 14"))
        assertFalse(source.contains("AURORA_INGRESS_LOCAL_PACKET_JSON_OPERATION"))
        assertEquals(4, Regex("\\bAURORA_INGRESS_LOCAL_PACKET_OPERATION\\b").findAll(source).count())
        assertTrue(source.contains("#define AURORA_MAX_LOCAL_PACKET_RESULT_BYTES (1024 * 1024)"))
        assertTrue(source.contains("maximum_output_bytes = 1 + AURORA_MAX_LOCAL_PACKET_RESULT_BYTES;"))
    }

    @Test
    fun bridgeCopiesAndSecurelyReleasesSensitiveNativeBuffers() {
        val source = bridgeSource()

        assertTrue(source.contains("static void aurora_secure_zero(void *value, size_t length)"))
        assertTrue(source.contains("volatile uint8_t *cursor"))
        assertTrue(source.contains("native_input = malloc((size_t)input_length);"))
        assertTrue(source.contains("GetByteArrayRegion(environment, input, 0, input_length"))
        assertFalse(source.contains("GetByteArrayElements"))
        assertTrue(source.contains("aurora_secure_zero(native_input, (size_t)input_length);"))

        val copyOutput = source.indexOf("SetByteArrayRegion(environment, result")
        val clearOutput = source.indexOf("AuroraCoreZeroFree(native_output, output_length);", copyOutput)
        val checkCopy = source.indexOf("ExceptionCheck(environment)", clearOutput)
        assertTrue(copyOutput >= 0)
        assertTrue(clearOutput > copyOutput)
        assertTrue(checkCopy > clearOutput)
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
