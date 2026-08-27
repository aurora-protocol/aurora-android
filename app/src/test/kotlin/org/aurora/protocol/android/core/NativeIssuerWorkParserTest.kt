package org.aurora.protocol.android.core

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun rejectsPortZero() {
        val encoded = """
            {
              "handle":7,
              "issuer_url":"https://issuer.example:0",
              "issuer_carrier_path":"/assets/issue",
              "request_body_base64":"AQ=="
            }
        """.trimIndent().toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            NativeIssuerWorkParser.decode(encoded)
        }
    }

    @Test
    fun rejectsAmbiguousIssuerOrigins() {
        listOf(
            "https://issuer.example:",
            "https://user@issuer.example",
            "https://issuer.example/",
            "https://issuer.example?",
            "https://issuer.example#",
        ).forEach { issuerUrl ->
            assertThrows(IllegalArgumentException::class.java) {
                NativeIssuerWorkParser.decode(issuerWork(issuerUrl = issuerUrl))
            }
        }
    }

    @Test
    fun rejectsNonCanonicalCarrierPaths() {
        listOf(
            "/",
            "/assets//issue",
            "/assets/issue/",
            "/assets/../issue",
            "/assets/%2e%2e/issue",
        ).forEach { carrierPath ->
            assertThrows(IllegalArgumentException::class.java) {
                NativeIssuerWorkParser.decode(issuerWork(carrierPath = carrierPath))
            }
        }
    }

    @Test
    fun rejectsIssuerComponentsOverTheCoreUtf8ByteLimit() {
        val oversizedOrigin = "https://${"a".repeat(2_041)}.example"
        val oversizedPath = "/${"é".repeat(1_024)}"

        assertThrows(IllegalArgumentException::class.java) {
            NativeIssuerWorkParser.decode(issuerWork(issuerUrl = oversizedOrigin))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeIssuerWorkParser.decode(issuerWork(carrierPath = oversizedPath))
        }
    }

    @Test
    fun rejectsNonCanonicalBase64() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            NativeIssuerWorkParser.decode(issuerWork(requestBodyBase64 = "AQ"))
        }

        assertTrue(error.message?.contains("invalid Core issuer work") == true)
    }

    @Test
    fun wrapsMalformedJsonAndUtf8AsInvalidCoreWork() {
        listOf(
            "{".toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28),
        ).forEach { encoded ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                NativeIssuerWorkParser.decode(encoded)
            }
            assertEquals("invalid Core issuer work", error.message)
        }
    }

    private fun issuerWork(
        issuerUrl: String = "https://issuer.example",
        carrierPath: String = "/assets/issue",
        requestBodyBase64: String = "AQ==",
    ): ByteArray = """
        {
          "handle":7,
          "issuer_url":"$issuerUrl",
          "issuer_carrier_path":"$carrierPath",
          "request_body_base64":"$requestBodyBase64"
        }
    """.trimIndent().toByteArray()
}
