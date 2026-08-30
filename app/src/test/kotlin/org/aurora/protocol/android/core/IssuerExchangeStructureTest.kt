package org.aurora.protocol.android.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssuerExchangeStructureTest {
    @Test
    fun httpsExchangeKeepsTypesEndpointAndSupportSeparate() {
        val types = typesSource()
        val exchange = exchangeCoreSource()
        val endpoint = endpointSource()
        val support = supportSource()

        assertTrue(types.contains("interface IssuerExchange"))
        assertTrue(types.contains("interface CancellableIssuerExchange"))
        assertTrue(types.contains("class IssuerExchangeTimeoutException"))
        assertTrue(exchange.contains("class HttpsIssuerExchange"))
        assertTrue(exchange.contains("override fun exchange(work: NativeIssuerWork)"))
        assertTrue(exchange.contains("issuerEndpointFor(work)"))
        assertTrue(endpoint.contains("fun issuerEndpointFor(work: NativeIssuerWork)"))
        assertTrue(support.contains("class ActiveIssuerConnection"))
        assertTrue(support.contains("object SystemIssuerDeadlineScheduler"))
        val httpConnection = httpConnectionSource()
        assertTrue(httpConnection.contains("class HttpsIssuerHttpConnection"))
        assertFalse(support.contains("class HttpsIssuerHttpConnection"))
        assertFalse(httpConnection.contains("class ActiveIssuerConnection"))
        assertFalse(types.contains("class HttpsIssuerExchange"))
        assertFalse(exchange.contains("class ActiveIssuerConnection"))
        assertFalse(exchange.contains("fun issuerEndpointFor(work: NativeIssuerWork)"))
        assertFalse(endpoint.contains("class HttpsIssuerExchange"))
        assertFalse(support.contains("override fun exchange(work: NativeIssuerWork)"))
    }

    @Test
    fun httpsExchangeKeepsLifecycleAndResponseSeparateFromExchangeCore() {
        val exchange = exchangeCoreSource()
        val lifecycle = lifecycleSource()
        val response = responseSource()

        assertTrue(exchange.contains("override fun cancel()"))
        assertTrue(lifecycle.contains("fun HttpsIssuerExchange.expire("))
        assertTrue(lifecycle.contains("fun HttpsIssuerExchange.finishExchange("))
        assertTrue(lifecycle.contains("fun HttpsIssuerExchange.releaseConnectionAfterCleanup("))
        assertTrue(response.contains("fun HttpsIssuerExchange.readBounded("))
        assertTrue(response.contains("fun isBinaryContentType("))
        assertFalse(exchange.contains("fun HttpsIssuerExchange.expire("))
        assertFalse(exchange.contains("fun HttpsIssuerExchange.readBounded("))
        assertFalse(lifecycle.contains("fun HttpsIssuerExchange.readBounded("))
        assertFalse(response.contains("fun HttpsIssuerExchange.finishExchange("))
    }

    @Test
    fun httpsTransportPinsBoundedPrivateBinaryPostBehavior() {
        val transport = httpConnectionSource()

        assertTrue(transport.contains("connection.connectTimeout = 10_000"))
        assertTrue(transport.contains("connection.readTimeout = 30_000"))
        assertTrue(transport.contains("connection.instanceFollowRedirects = false"))
        assertTrue(transport.contains("connection.useCaches = false"))
        assertTrue(transport.contains("connection.allowUserInteraction = false"))
        assertTrue(transport.contains("connection.setFixedLengthStreamingMode(requestBody.size)"))
        assertTrue(transport.contains("connection.setRequestProperty(\"Content-Type\", \"application/octet-stream\")"))
        assertTrue(transport.contains("connection.setRequestProperty(\"Accept\", \"application/octet-stream\")"))
        assertTrue(transport.contains("connection.setRequestProperty(\"Cache-Control\", \"no-store\")"))
        assertTrue(transport.contains("connection.errorStream"))
        assertTrue(transport.contains("connection.disconnect()"))
    }

    private fun typesSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/IssuerExchange.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/IssuerExchange.kt",
    )

    private fun exchangeSource(): String = listOf(
        exchangeCoreSource(),
        lifecycleSource(),
        responseSource(),
    ).joinToString("\n")

    private fun exchangeCoreSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/HttpsIssuerExchange.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/HttpsIssuerExchange.kt",
    )

    private fun lifecycleSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/HttpsIssuerExchangeLifecycle.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/HttpsIssuerExchangeLifecycle.kt",
    )

    private fun responseSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/HttpsIssuerExchangeResponse.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/HttpsIssuerExchangeResponse.kt",
    )

    private fun endpointSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/HttpsIssuerExchangeEndpoint.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/HttpsIssuerExchangeEndpoint.kt",
    )

    private fun supportSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/IssuerExchangeSupport.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/IssuerExchangeSupport.kt",
    )

    private fun httpConnectionSource(): String = readFirstExisting(
        "src/main/kotlin/org/aurora/protocol/android/core/HttpsIssuerHttpConnection.kt",
        "app/src/main/kotlin/org/aurora/protocol/android/core/HttpsIssuerHttpConnection.kt",
    )

    private fun readFirstExisting(vararg relativePaths: String): String =
        relativePaths
            .map(::File)
            .firstOrNull(File::isFile)
            ?.readText()
            ?: throw IllegalStateException("source file is unavailable")
}
