package org.aurora.protocol.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FileDescriptorTunnelDeviceTest {
    @Test
    fun successfulReadsClearOnlyPopulatedPacketBytes() {
        assertEquals(1280, tunnelReadBufferClearBytes(readCount = 1280, capacity = 65_535))
        assertEquals(65_535, tunnelReadBufferClearBytes(readCount = 70_000, capacity = 65_535))
    }

    @Test
    fun closedOrEmptyReadsHaveNoPopulatedBytesToClear() {
        assertEquals(0, tunnelReadBufferClearBytes(readCount = -1, capacity = 65_535))
        assertEquals(0, tunnelReadBufferClearBytes(readCount = 0, capacity = 65_535))
    }

    @Test
    fun failedReadsClearTheWholeReusableBuffer() {
        assertEquals(65_535, tunnelReadBufferClearBytes(readCount = null, capacity = 65_535))
    }

    @Test
    fun rejectsAnInvalidBufferCapacity() {
        assertThrows(IllegalArgumentException::class.java) {
            tunnelReadBufferClearBytes(readCount = 1, capacity = -1)
        }
    }
}
