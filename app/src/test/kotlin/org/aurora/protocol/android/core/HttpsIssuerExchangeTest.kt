package org.aurora.protocol.android.core

import java.io.ByteArrayInputStream
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

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

    @Test
    fun revalidatesIssuerOriginsAndCanonicalCarrierPathsBeforeOpeningAConnection() {
        val exchange = HttpsIssuerExchange(IssuerHttpConnectionFactory { throw AssertionError("must not open") })
        val invalidWork = listOf(
            NativeIssuerWork(7, URL("https://issuer.example:0"), "/assets/issue", byteArrayOf(0x30)),
            NativeIssuerWork(7, URL("https://issuer.example:"), "/assets/issue", byteArrayOf(0x30)),
            NativeIssuerWork(7, URL("https://user@issuer.example"), "/assets/issue", byteArrayOf(0x30)),
            NativeIssuerWork(7, URL("https://issuer.example/"), "/assets/issue", byteArrayOf(0x30)),
            NativeIssuerWork(7, URL("https://issuer.example/base"), "/assets/issue", byteArrayOf(0x30)),
            NativeIssuerWork(7, URL("https://issuer.example?mode=test"), "/assets/issue", byteArrayOf(0x30)),
            NativeIssuerWork(7, URL("https://issuer.example?"), "/assets/issue", byteArrayOf(0x30)),
            NativeIssuerWork(7, URL("https://issuer.example#fragment"), "/assets/issue", byteArrayOf(0x30)),
            NativeIssuerWork(7, URL("https://issuer.example#"), "/assets/issue", byteArrayOf(0x30)),
            NativeIssuerWork(7, URL("https://issuer.example"), "/assets/../issue", byteArrayOf(0x30)),
            NativeIssuerWork(7, URL("https://issuer.example"), "/assets/%2e%2e/issue", byteArrayOf(0x30)),
            NativeIssuerWork(7, URL("https://issuer.example"), "/assets//issue", byteArrayOf(0x30)),
            NativeIssuerWork(7, URL("https://issuer.example"), "/assets/issue/", byteArrayOf(0x30)),
        )

        invalidWork.forEach { work ->
            try {
                assertThrows(IllegalArgumentException::class.java) { exchange.exchange(work) }
            } finally {
                work.close()
            }
        }
    }

    @Test
    fun rejectsIssuerComponentsOverTheCoreUtf8ByteLimit() {
        val exchange = HttpsIssuerExchange(IssuerHttpConnectionFactory { throw AssertionError("must not open") })
        val oversizedOrigin = NativeIssuerWork(
            7,
            URL("https://${"a".repeat(2_041)}.example"),
            "/assets/issue",
            byteArrayOf(0x30),
        )
        val oversizedPath = NativeIssuerWork(
            7,
            URL("https://issuer.example"),
            "/${"é".repeat(1_024)}",
            byteArrayOf(0x30),
        )

        try {
            assertThrows(IllegalArgumentException::class.java) { exchange.exchange(oversizedOrigin) }
            assertThrows(IllegalArgumentException::class.java) { exchange.exchange(oversizedPath) }
        } finally {
            oversizedOrigin.close()
            oversizedPath.close()
        }
    }

    @Test
    fun rejectsAConcurrentExchangeWithoutReplacingTheCancellableConnection() {
        val connection = BlockingConnection()
        val opens = AtomicInteger()
        val exchange = HttpsIssuerExchange(IssuerHttpConnectionFactory {
            opens.incrementAndGet()
            connection
        })
        val firstWork = NativeIssuerWork(7, URL("https://issuer.example"), "/assets/issue", byteArrayOf(0x30))
        val secondWork = NativeIssuerWork(8, URL("https://issuer.example"), "/assets/issue", byteArrayOf(0x31))
        val firstFailure = AtomicReference<Throwable?>()
        val worker = thread(start = true, name = "issuer-exchange-test") {
            try {
                exchange.exchange(firstWork).fill(0)
            } catch (error: Throwable) {
                firstFailure.set(error)
            }
        }

        try {
            assertTrue(connection.started.await(2, TimeUnit.SECONDS))
            val error = assertThrows(IllegalStateException::class.java) { exchange.exchange(secondWork) }
            assertEquals("issuer exchange is already in progress", error.message)
            assertEquals(1, opens.get())
        } finally {
            connection.release.countDown()
            worker.join(2_000)
            firstWork.close()
            secondWork.close()
        }
        assertFalse(worker.isAlive)
        assertNull(firstFailure.get())
        assertTrue(connection.closed)
    }

    @Test
    fun cancellationClosesTheActiveExchangeAndPreventsReuse() {
        val connection = CancellationBlockingConnection()
        val opens = AtomicInteger()
        val exchange = HttpsIssuerExchange(IssuerHttpConnectionFactory {
            opens.incrementAndGet()
            connection
        })
        val work = NativeIssuerWork(7, URL("https://issuer.example"), "/assets/issue", byteArrayOf(0x30))
        val workerFailure = AtomicReference<Throwable?>()
        val worker = thread(start = true, name = "issuer-cancellation-test") {
            try {
                exchange.exchange(work).fill(0)
            } catch (error: Throwable) {
                workerFailure.set(error)
            }
        }

        try {
            assertTrue(connection.started.await(2, TimeUnit.SECONDS))
            exchange.cancel()
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertTrue(connection.closed)
            assertTrue(workerFailure.get() is IllegalStateException)
            assertThrows(IllegalStateException::class.java) { exchange.exchange(work) }
            assertEquals(1, opens.get())
        } finally {
            connection.release.countDown()
            worker.join(2_000)
            work.close()
        }
    }

    @Test
    fun preservesThePrimaryFailureAndClosesAnUnreadResponseBody() {
        val body = ThrowingCloseInputStream(byteArrayOf(0x50, 0x60))
        val connection = FakeConnection(
            IssuerHttpResponse(200, 2, body, "text/html"),
            closeFailure = IllegalStateException("connection cleanup failed"),
        )
        val exchange = HttpsIssuerExchange(IssuerHttpConnectionFactory { connection })
        val work = NativeIssuerWork(7, URL("https://issuer.example"), "/assets/issue", byteArrayOf(0x30))

        try {
            val error = assertThrows(IllegalArgumentException::class.java) { exchange.exchange(work) }
            assertEquals("issuer response content type is invalid", error.message)
            assertEquals(
                listOf("response body cleanup failed", "connection cleanup failed"),
                error.suppressed.map { it.message },
            )
            assertTrue(body.closed)
            assertTrue(connection.closed)
        } finally {
            work.close()
        }
    }

    private class FakeConnection(
        private val response: IssuerHttpResponse,
        private val closeFailure: RuntimeException? = null,
    ) : IssuerHttpConnection {
        lateinit var request: ByteArray
        var closed = false

        override fun post(requestBody: ByteArray): IssuerHttpResponse {
            request = requestBody.copyOf()
            return response
        }

        override fun close() {
            closed = true
            closeFailure?.let { throw it }
        }
    }

    private class BlockingConnection : IssuerHttpConnection {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var closed = false

        override fun post(requestBody: ByteArray): IssuerHttpResponse {
            started.countDown()
            check(release.await(2, TimeUnit.SECONDS)) { "timed out waiting to finish issuer response" }
            return IssuerHttpResponse(
                200,
                1,
                ByteArrayInputStream(byteArrayOf(0x50)),
                "application/octet-stream",
            )
        }

        override fun close() {
            closed = true
        }
    }

    private class CancellationBlockingConnection : IssuerHttpConnection {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var closed = false

        override fun post(requestBody: ByteArray): IssuerHttpResponse {
            started.countDown()
            check(release.await(2, TimeUnit.SECONDS)) { "timed out waiting for issuer cancellation" }
            throw IllegalStateException("issuer connection was cancelled")
        }

        override fun close() {
            closed = true
            release.countDown()
        }
    }

    private class ThrowingCloseInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            throw IllegalStateException("response body cleanup failed")
        }
    }
}
