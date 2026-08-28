package org.aurora.protocol.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeCoreResponseTest {
    @Test
    fun copiesSuccessfulPayloadAndClearsTheRawNativeResult() {
        val raw = byteArrayOf(0, 0x41, 0x42)

        val response = NativeCoreResponse.decode(raw)
        try {
            assertEquals(CoreStatus.OK, response.status)
            assertArrayEquals(byteArrayOf(0x41, 0x42), response.payload)
            assertArrayEquals(byteArrayOf(0, 0, 0), raw)
        } finally {
            response.close()
        }
    }

    @Test
    fun decodesStatusOnlyConflictAndErrorResultsAndClearsTheirRawFrames() {
        listOf(CoreStatus.CONFLICT, CoreStatus.ERROR).forEach { status ->
            val raw = byteArrayOf(status.wireValue.toByte())

            NativeCoreResponse.decode(raw).use { response ->
                assertEquals(status, response.status)
                assertArrayEquals(ByteArray(0), response.payload)
            }
            assertArrayEquals(ByteArray(1), raw)
        }
    }

    @Test
    fun rejectsInvalidOrPayloadBearingErrorResults() {
        val invalidStatus = byteArrayOf(3)
        val payloadBearingConflict = byteArrayOf(1, 0x01)
        val payloadBearingError = byteArrayOf(2, 0x01)

        listOf(invalidStatus, payloadBearingConflict, payloadBearingError).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                NativeCoreResponse.decode(raw)
            }
            assertArrayEquals(ByteArray(raw.size), raw)
        }
    }

    @Test
    fun transfersPayloadOwnershipWithoutClearingTheTransferredPacket() {
        val response = CoreResponse(CoreStatus.OK, byteArrayOf(0x41, 0x42))

        val packet = response.takePayload()
        response.close()

        assertArrayEquals(byteArrayOf(0x41, 0x42), packet)
        assertArrayEquals(ByteArray(0), response.payload)
        packet.fill(0)
    }
}
