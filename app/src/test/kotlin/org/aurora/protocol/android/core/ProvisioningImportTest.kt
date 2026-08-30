package org.aurora.protocol.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningImportTest {
    @Test
    fun decodesBoundedCanonicalBase64Requests() {
        val encoded = "AQID".toCharArray()
        val request = ProvisioningImport.decode(encoded)
        try {
            assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), request)
            assertArrayEquals(CharArray(4), encoded)
        } finally {
            request.fill(0)
        }
    }

    @Test
    fun rejectsEmptyAndNonCanonicalRequests() {
        assertThrows(IllegalArgumentException::class.java) { ProvisioningImport.decode(CharArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { ProvisioningImport.decode("AQI".toCharArray()) }
        assertThrows(IllegalArgumentException::class.java) { ProvisioningImport.decode("AQID\n".toCharArray()) }
    }

    @Test
    fun rejectsNonAsciiCharactersAndClearsTheInput() {
        val encoded = charArrayOf('A', 'Q', 'é', 'D')

        val error = assertThrows(IllegalArgumentException::class.java) { ProvisioningImport.decode(encoded) }

        assertEquals("invalid provisioning import encoding", error.message)
        assertArrayEquals(CharArray(4), encoded)
    }

    @Test
    fun rejectsNonCanonicalPaddingBitsAndClearsTheInput() {
        listOf("AR==", "AQJ=").forEach { value ->
            val encoded = value.toCharArray()

            assertThrows(IllegalArgumentException::class.java) {
                ProvisioningImport.decode(encoded)
            }

            assertArrayEquals(CharArray(value.length), encoded)
        }
    }

    @Test
    fun clearsRejectedProvisioningCharacters() {
        val encoded = "AQI".toCharArray()

        assertThrows(IllegalArgumentException::class.java) { ProvisioningImport.decode(encoded) }

        assertArrayEquals(CharArray(3), encoded)
    }

    @Test
    fun exposesTheAllocationGuardUsedByThePastePath() {
        assertFalse(ProvisioningImport.hasValidEncodedLength(0))
        assertTrue(ProvisioningImport.hasValidEncodedLength(ProvisioningImport.maximumEncodedCharacters))
        assertFalse(ProvisioningImport.hasValidEncodedLength(ProvisioningImport.maximumEncodedCharacters + 1))
    }
}
