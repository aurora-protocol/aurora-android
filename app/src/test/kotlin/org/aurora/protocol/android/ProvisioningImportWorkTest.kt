package org.aurora.protocol.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProvisioningImportWorkTest {
    @Test
    fun interruptedRequestIsClearedWithoutPersisting() {
        val request = byteArrayOf(1, 2, 3)
        var persistCalls = 0

        val result = consumeProvisioningImportRequest(
            request = request,
            interrupted = { true },
            reserveAndPersist = {
                persistCalls++
                7L
            },
        )

        assertNull(result)
        assertEquals(0, persistCalls)
        assertArrayEquals(ByteArray(3), request)
    }

    @Test
    fun persistedRequestIsClearedAfterUse() {
        val request = byteArrayOf(1, 2, 3)

        val result = consumeProvisioningImportRequest(
            request = request,
            interrupted = { false },
            reserveAndPersist = { ownedRequest ->
                assertArrayEquals(byteArrayOf(1, 2, 3), ownedRequest)
                11L
            },
        )

        assertEquals(11L, result)
        assertArrayEquals(ByteArray(3), request)
    }

    @Test
    fun failedRequestIsClearedBeforePropagatingTheFailure() {
        val request = byteArrayOf(1, 2, 3)

        assertThrows(IllegalStateException::class.java) {
            consumeProvisioningImportRequest(
                request = request,
                interrupted = { false },
                reserveAndPersist = { throw IllegalStateException("simulated failure") },
            )
        }

        assertArrayEquals(ByteArray(3), request)
    }
}
