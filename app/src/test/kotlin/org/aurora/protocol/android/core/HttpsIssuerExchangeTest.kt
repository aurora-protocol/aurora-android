package org.aurora.protocol.android.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpsIssuerExchangeTest {
    @Test
    fun postsOpaqueIssuerWorkToTheDeclaredHttpsOriginAndPath() {
        val connection = FakeConnection(
            IssuerHttpResponse(200, 2, ByteArrayInputStream(byteArrayOf(0x50, 0x60)), "application/octet-stream"),
        )
        var endpoint: URL? = null
        val exchange = HttpsIssuerExchange(IssuerHttpConnectionFactory {
            endpoint = it
            connection
        })
        val work = NativeIssuerWork(7, URL("https://issuer.example:8443"), "/assets/issue", byteArrayOf(0x30, 0x40))

        val response = exchange.exchange(work)
        try {
            assertEquals("https://issuer.example:8443/assets/issue", endpoint.toString())
            assertArrayEquals(byteArrayOf(0x30, 0x40), connection.request)
            assertArrayEquals(byteArrayOf(0x50, 0x60), response)
            assertTrue(connection.closed)
        } finally {
            response.fill(0)
            work.close()
        }
    }

    @Test
    fun rejectsNonSuccessfulAndOversizedIssuerResponses() {
        val rejected = FakeConnection(IssuerHttpResponse(503, 0, null, "application/octet-stream"))
        val tooLarge = FakeConnection(
            IssuerHttpResponse(200, 1_048_577, ByteArrayInputStream(ByteArray(0)), "application/octet-stream"),
        )
        val connections = ArrayDeque(listOf(rejected, tooLarge))
        val exchange = HttpsIssuerExchange(IssuerHttpConnectionFactory { connections.removeFirst() })
        val work = NativeIssuerWork(7, URL("https://issuer.example"), "/assets/issue", byteArrayOf(0x30))

        try {
            assertThrows(IllegalArgumentException::class.java) { exchange.exchange(work) }
            assertThrows(IllegalArgumentException::class.java) { exchange.exchange(work) }
            assertTrue(rejected.closed)
            assertTrue(tooLarge.closed)
        } finally {
            work.close()
        }
    }

    @Test
    fun rejectsIssuerResponsesWithNonBinaryContentType() {
        val rejected = FakeConnection(
            IssuerHttpResponse(200, 2, ByteArrayInputStream(byteArrayOf(0x50, 0x60)), "text/html"),
        )
        val exchange = HttpsIssuerExchange(IssuerHttpConnectionFactory { rejected })
        val work = NativeIssuerWork(7, URL("https://issuer.example"), "/assets/issue", byteArrayOf(0x30))

        try {
            assertThrows(IllegalArgumentException::class.java) { exchange.exchange(work) }
            assertTrue(rejected.closed)
        } finally {
            work.close()
        }
    }

    @Test
    fun rejectsIssuerWorkThatDoesNotUseHttps() {
        val exchange = HttpsIssuerExchange(IssuerHttpConnectionFactory { throw AssertionError("must not open") })
        val work = NativeIssuerWork(7, URL("http://issuer.example"), "/assets/issue", byteArrayOf(0x30))

        try {
            assertThrows(IllegalArgumentException::class.java) { exchange.exchange(work) }
        } finally {
            work.close()
        }
    }

    private class FakeConnection(private val response: IssuerHttpResponse) : IssuerHttpConnection {
        lateinit var request: ByteArray
        var closed = false

        override fun post(requestBody: ByteArray): IssuerHttpResponse {
            request = requestBody.copyOf()
            return response
        }

        override fun close() {
            closed = true
        }
    }
}
