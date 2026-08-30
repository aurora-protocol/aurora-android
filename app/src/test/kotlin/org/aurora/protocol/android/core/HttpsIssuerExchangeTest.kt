package org.aurora.protocol.android.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
        val rejectedBody = CloseTrackingInputStream(byteArrayOf(0x50, 0x60))
        val rejected = FakeConnection(
            IssuerHttpResponse(503, 2, rejectedBody, "application/octet-stream"),
        )
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
            assertTrue(rejectedBody.closed)
            assertTrue(tooLarge.closed)
        } finally {
            work.close()
        }
    }

    @Test
    fun rejectsOversizedUnknownLengthResponseAndScrubsTheReadBuffer() {
        val body = ObservingByteArrayInputStream(
            ByteArray(HttpsIssuerExchange.maximumIssuerResponseBytes + 1) { 0x5a },
        )
        val connection = FakeConnection(
            IssuerHttpResponse(200, -1, body, "application/octet-stream"),
        )
        val exchange = HttpsIssuerExchange(IssuerHttpConnectionFactory { connection })
        val work = NativeIssuerWork(7, URL("https://issuer.example"), "/assets/issue", byteArrayOf(0x30))

        try {
            val error = assertThrows(IllegalArgumentException::class.java) { exchange.exchange(work) }
            assertEquals("issuer response exceeds size limit", error.message)
            assertTrue(connection.closed)
            assertTrue(requireNotNull(body.observedReadBuffer).all { it == 0.toByte() })
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
    fun totalDeadlineClosesASlowDripResponseScrubsItsBufferAndAllowsReuse() {
        val slow = SlowDripConnection()
        val completed = FakeConnection(
            IssuerHttpResponse(200, 1, ByteArrayInputStream(byteArrayOf(0x61)), "application/octet-stream"),
        )
        val connections = ArrayDeque<IssuerHttpConnection>(listOf(slow, completed))
        val scheduler = ManualDeadlineScheduler()
        val now = AtomicLong(0)
        val exchange = HttpsIssuerExchange(
            connectionFactory = IssuerHttpConnectionFactory { connections.removeFirst() },
            exchangeTimeoutNanos = 100,
            monotonicNanos = now::get,
            deadlineScheduler = scheduler,
        )
        val work = NativeIssuerWork(7, URL("https://issuer.example"), "/assets/issue", byteArrayOf(0x30))
        val firstFailure = AtomicReference<Throwable?>()
        val worker = thread(start = true, name = "issuer-deadline-test") {
            try {
                exchange.exchange(work).fill(0)
            } catch (error: Throwable) {
                firstFailure.set(error)
            }
        }

        try {
            assertTrue(slow.body.waitingForNextByte.await(2, TimeUnit.SECONDS))
            now.set(100)
            scheduler.fireNext()
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertTrue(firstFailure.get() is IssuerExchangeTimeoutException)
            assertEquals(1, slow.closeCalls.get())
            assertTrue(requireNotNull(slow.body.observedReadBuffer).all { it == 0.toByte() })
            assertEquals(0, scheduler.activeTaskCount())

            val response = exchange.exchange(work)
            try {
                assertArrayEquals(byteArrayOf(0x61), response)
            } finally {
                response.fill(0)
            }
            assertEquals(1, completed.closeCalls)
            assertEquals(0, scheduler.activeTaskCount())
        } finally {
            slow.body.close()
            worker.join(2_000)
            work.close()
        }
    }

    @Test
    fun monotonicDeadlineStopsADripWhenWatchdogDeliveryIsDelayed() {
        val now = AtomicLong(0)
        val body = AdvancingDripInputStream(now)
        val connection = FakeConnection(
            IssuerHttpResponse(200, -1, body, "application/octet-stream"),
        )
        val scheduler = ManualDeadlineScheduler()
        val exchange = HttpsIssuerExchange(
            connectionFactory = IssuerHttpConnectionFactory { connection },
            exchangeTimeoutNanos = 100,
            monotonicNanos = now::get,
            deadlineScheduler = scheduler,
        )
        val work = NativeIssuerWork(7, URL("https://issuer.example"), "/assets/issue", byteArrayOf(0x30))

        try {
            assertThrows(IssuerExchangeTimeoutException::class.java) { exchange.exchange(work) }
            assertEquals(1, connection.closeCalls)
            assertTrue(requireNotNull(body.observedReadBuffer).all { it == 0.toByte() })
            assertEquals(0, scheduler.activeTaskCount())
        } finally {
            work.close()
        }
    }

    @Test
    fun timeoutRetainsExchangeOwnershipUntilTheClosingConnectionFinishes() {
        val closeRelease = CountDownLatch(1)
        val slow = SlowDripConnection(closeRelease)
        val completed = FakeConnection(
            IssuerHttpResponse(200, 1, ByteArrayInputStream(byteArrayOf(0x62)), "application/octet-stream"),
        )
        val opens = AtomicInteger()
        val connections = ArrayDeque<IssuerHttpConnection>(listOf(slow, completed))
        val scheduler = ManualDeadlineScheduler()
        val now = AtomicLong(0)
        val exchange = HttpsIssuerExchange(
            connectionFactory = IssuerHttpConnectionFactory {
                opens.incrementAndGet()
                connections.removeFirst()
            },
            exchangeTimeoutNanos = 100,
            monotonicNanos = now::get,
            deadlineScheduler = scheduler,
        )
        val work = NativeIssuerWork(7, URL("https://issuer.example"), "/assets/issue", byteArrayOf(0x30))
        val firstFailure = AtomicReference<Throwable?>()
        val worker = thread(start = true, name = "issuer-timeout-owner-test") {
            try {
                exchange.exchange(work).fill(0)
            } catch (error: Throwable) {
                firstFailure.set(error)
            }
        }
        var deadlineWorker: Thread? = null

        try {
            assertTrue(slow.body.waitingForNextByte.await(2, TimeUnit.SECONDS))
            now.set(100)
            deadlineWorker = thread(start = true, name = "issuer-timeout-close-test") {
                scheduler.fireNext()
            }
            assertTrue(slow.closeStarted.await(2, TimeUnit.SECONDS))
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertTrue(firstFailure.get() is IssuerExchangeTimeoutException)
            val overlap = assertThrows(IllegalStateException::class.java) { exchange.exchange(work) }
            assertEquals("issuer exchange is already in progress", overlap.message)
            assertEquals(1, opens.get())

            closeRelease.countDown()
            deadlineWorker.join(2_000)
            assertFalse(deadlineWorker.isAlive)
            assertEquals(1, slow.closeCalls.get())

            val response = exchange.exchange(work)
            try {
                assertArrayEquals(byteArrayOf(0x62), response)
            } finally {
                response.fill(0)
            }
            assertEquals(2, opens.get())
            assertEquals(1, completed.closeCalls)
            assertEquals(0, scheduler.activeTaskCount())
        } finally {
            closeRelease.countDown()
            slow.body.close()
            deadlineWorker?.join(2_000)
            worker.join(2_000)
            work.close()
        }
    }

    @Test
    fun asynchronousCloseFailurePermanentlyPoisonsTheExchange() {
        val closeRelease = CountDownLatch(1)
        val slow = SlowDripConnection(
            closeRelease = closeRelease,
            closeFailure = IOException("connection close failed"),
        )
        val opens = AtomicInteger()
        val scheduler = ManualDeadlineScheduler()
        val now = AtomicLong(0)
        val exchange = HttpsIssuerExchange(
            connectionFactory = IssuerHttpConnectionFactory {
                opens.incrementAndGet()
                slow
            },
            exchangeTimeoutNanos = 100,
            monotonicNanos = now::get,
            deadlineScheduler = scheduler,
        )
        val work = NativeIssuerWork(7, URL("https://issuer.example"), "/assets/issue", byteArrayOf(0x30))
        val firstFailure = AtomicReference<Throwable?>()
        val worker = thread(start = true, name = "issuer-timeout-close-failure-test") {
            try {
                exchange.exchange(work).fill(0)
            } catch (error: Throwable) {
                firstFailure.set(error)
            }
        }
        val deadlineWorker = AtomicReference<Thread?>()

        try {
            assertTrue(slow.body.waitingForNextByte.await(2, TimeUnit.SECONDS))
            now.set(100)
            deadlineWorker.set(thread(start = true, name = "issuer-failing-close-test") {
                scheduler.fireNext()
            })
            assertTrue(slow.closeStarted.await(2, TimeUnit.SECONDS))
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertTrue(firstFailure.get() is IssuerExchangeTimeoutException)

            closeRelease.countDown()
            deadlineWorker.get()?.join(2_000)
            val poisoned = assertThrows(IllegalStateException::class.java) { exchange.exchange(work) }
            assertEquals("issuer exchange was cancelled", poisoned.message)
            assertEquals(1, opens.get())
            assertEquals(1, slow.closeCalls.get())
        } finally {
            closeRelease.countDown()
            slow.body.close()
            deadlineWorker.get()?.join(2_000)
            worker.join(2_000)
            work.close()
        }
    }

    @Test
    fun completionDisarmsADeadlineRacingWithFinalCleanup() {
        val connection = FakeConnection(
            IssuerHttpResponse(200, 1, ByteArrayInputStream(byteArrayOf(0x63)), "application/octet-stream"),
        )
        val scheduler = CompletionRaceDeadlineScheduler()
        val exchange = HttpsIssuerExchange(
            connectionFactory = IssuerHttpConnectionFactory { connection },
            exchangeTimeoutNanos = 100,
            monotonicNanos = { 0 },
            deadlineScheduler = scheduler,
        )
        val work = NativeIssuerWork(7, URL("https://issuer.example"), "/assets/issue", byteArrayOf(0x30))
        val response = AtomicReference<ByteArray?>()
        val failure = AtomicReference<Throwable?>()
        val worker = thread(start = true, name = "issuer-completion-deadline-race-test") {
            try {
                response.set(exchange.exchange(work))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        try {
            assertTrue(scheduler.cancelStarted.await(2, TimeUnit.SECONDS))
            scheduler.fireWhileCancellationIsPending()
            assertEquals(0, connection.closeCalls)
            scheduler.allowCancellation.countDown()
            worker.join(2_000)
            assertFalse(worker.isAlive)
            assertNull(failure.get())
            assertArrayEquals(byteArrayOf(0x63), response.get())
            assertEquals(1, connection.closeCalls)
        } finally {
            scheduler.allowCancellation.countDown()
            worker.join(2_000)
            response.getAndSet(null)?.fill(0)
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
        var closeCalls = 0

        override fun post(requestBody: ByteArray): IssuerHttpResponse {
            request = requestBody.copyOf()
            return response
        }

        override fun close() {
            closed = true
            closeCalls += 1
            closeFailure?.let { throw it }
        }
    }

    private class SlowDripConnection(
        private val closeRelease: CountDownLatch? = null,
        private val closeFailure: IOException? = null,
    ) : IssuerHttpConnection {
        val body = SlowDripInputStream()
        val closeStarted = CountDownLatch(1)
        val closeCalls = AtomicInteger()

        override fun post(requestBody: ByteArray): IssuerHttpResponse = IssuerHttpResponse(
            200,
            -1,
            body,
            "application/octet-stream",
        )

        override fun close() {
            closeCalls.incrementAndGet()
            body.close()
            closeStarted.countDown()
            closeRelease?.let { release ->
                check(release.await(2, TimeUnit.SECONDS)) { "timed out waiting to finish connection close" }
            }
            closeFailure?.let { throw it }
        }
    }

    private class SlowDripInputStream : java.io.InputStream() {
        val waitingForNextByte = CountDownLatch(1)
        val release = CountDownLatch(1)

        @Volatile
        var observedReadBuffer: ByteArray? = null

        @Volatile
        private var closed = false

        private var reads = 0

        override fun read(): Int = throw AssertionError("bulk reads are required")

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            check(length > 0)
            if (reads++ == 0) {
                target[offset] = 0x5a
                observedReadBuffer = target
                return 1
            }
            waitingForNextByte.countDown()
            check(release.await(2, TimeUnit.SECONDS)) { "timed out waiting for slow response close" }
            if (closed) {
                throw IOException("slow response was closed")
            }
            return -1
        }

        override fun close() {
            closed = true
            release.countDown()
        }
    }

    private class AdvancingDripInputStream(
        private val now: AtomicLong,
    ) : java.io.InputStream() {
        var observedReadBuffer: ByteArray? = null
            private set
        private var reads = 0

        override fun read(): Int = throw AssertionError("bulk reads are required")

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            check(length > 0)
            observedReadBuffer = target
            return when (reads++) {
                0 -> {
                    now.set(50)
                    target[offset] = 0x5a
                    1
                }
                1 -> {
                    now.set(100)
                    target[offset] = 0x6a
                    1
                }
                else -> -1
            }
        }
    }

    private class ManualDeadlineScheduler : IssuerDeadlineScheduler {
        private val tasks = mutableListOf<ManualDeadlineTask>()

        override fun schedule(delayNanos: Long, action: () -> Unit): IssuerDeadlineTask {
            check(delayNanos > 0)
            return ManualDeadlineTask(action).also(tasks::add)
        }

        fun fireNext() {
            tasks.first { it.active }.fire()
        }

        fun activeTaskCount(): Int = tasks.count { it.active }
    }

    private class ManualDeadlineTask(
        private val action: () -> Unit,
    ) : IssuerDeadlineTask {
        var active = true
            private set

        fun fire() {
            check(active)
            active = false
            action()
        }

        override fun cancel() {
            active = false
        }
    }

    private class CompletionRaceDeadlineScheduler : IssuerDeadlineScheduler {
        val cancelStarted = CountDownLatch(1)
        val allowCancellation = CountDownLatch(1)
        private lateinit var action: () -> Unit

        override fun schedule(delayNanos: Long, action: () -> Unit): IssuerDeadlineTask {
            check(delayNanos > 0)
            this.action = action
            return IssuerDeadlineTask {
                cancelStarted.countDown()
                check(allowCancellation.await(2, TimeUnit.SECONDS)) {
                    "timed out waiting to finish deadline cancellation"
                }
            }
        }

        fun fireWhileCancellationIsPending() {
            action()
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

    private class CloseTrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class ObservingByteArrayInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var observedReadBuffer: ByteArray? = null
            private set

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            observedReadBuffer = target
            return super.read(target, offset, length)
        }
    }
}
