package org.aurora.protocol.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProvisioningImportTest {
    @Test
    fun decodesBoundedCanonicalBase64Requests() {
        val request = ProvisioningImport.decode("AQID")
        try {
            assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), request)
        } finally {
            request.fill(0)
        }
    }

    @Test
    fun rejectsEmptyAndNonCanonicalRequests() {
        assertThrows(IllegalArgumentException::class.java) { ProvisioningImport.decode("") }
        assertThrows(IllegalArgumentException::class.java) { ProvisioningImport.decode("AQI") }
        assertThrows(IllegalArgumentException::class.java) { ProvisioningImport.decode("AQID\n") }
    }
}
