package org.aurora.protocol.android.core

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeIssuerWorkParserTest {
    @Test
    fun decodesStrictHttpsIssuerWork() {
        val request = byteArrayOf(0x01, 0x02, 0x03)
        val encoded = """
            {
              "handle":7,
              "issuer_url":"https://issuer.example",
              "issuer_carrier_path":"/assets/issue",
              "request_body_base64":"${Base64.getEncoder().encodeToString(request)}"
            }
        """.trimIndent().toByteArray()

        val work = NativeIssuerWorkParser.decode(encoded)
        try {
            assertEquals(7L, work.handle)
            assertEquals("https://issuer.example", work.issuerUrl.toString())
            assertEquals("/assets/issue", work.issuerCarrierPath)
            assertArrayEquals(request, work.requestBody)
        } finally {
            work.close()
        }
    }

    @Test
    fun rejectsIssuerWorkOutsideThePinnedOriginAndPathShape() {
        val request = Base64.getEncoder().encodeToString(byteArrayOf(0x01))
        val encoded = """
            {
              "handle":7,
              "issuer_url":"https://user@issuer.example/path",
              "issuer_carrier_path":"//other.example/request",
              "request_body_base64":"$request"
            }
        """.trimIndent().toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            NativeIssuerWorkParser.decode(encoded)
        }
    }
}
