package org.aurora.protocol.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLocalPacketsParserTest {
    @Test
    fun decodesBoundedCorePacketsAndClearsTheOwnedEnvelope() {
        val first = byteArrayOf(0x45, 0x00, 0x00, 0x14)
        val second = byteArrayOf(0x60, 0x00, 0x00, 0x00)
        val encoded = encodePacketList(first, second)

        val packets = NativeLocalPacketsParser.decode(encoded)

        try {
            assertEquals(2, packets.size)
            assertArrayEquals(first, packets[0])
            assertArrayEquals(second, packets[1])
            assertCleared(encoded)
        } finally {
            packets.forEach { it.fill(0) }
        }
    }

    @Test
    fun acceptsTheCanonicalEmptyPacketList() {
        val encoded = byteArrayOf(0x00)

        assertTrue(NativeLocalPacketsParser.decode(encoded).isEmpty())
        assertCleared(encoded)
    }

    @Test
    fun acceptsTheTwoByteCanonicalMaximumPacketCount() {
        val sourcePackets = Array(maximumPacketCount) { index -> byteArrayOf(index.toByte()) }
        val encoded = encodePacketList(*sourcePackets)
        assertArrayEquals(byteArrayOf(0x40, 0x40), encoded.copyOfRange(0, 2))

        val packets = NativeLocalPacketsParser.decode(encoded)

        try {
            assertEquals(maximumPacketCount, packets.size)
            sourcePackets.indices.forEach { index ->
                assertArrayEquals(sourcePackets[index], packets[index])
            }
            assertCleared(encoded)
        } finally {
            packets.forEach { it.fill(0) }
        }
    }

    @Test
    fun acceptsTheMaximumPacketSize() {
        val packet = ByteArray(maximumPacketBytes) { index -> index.toByte() }
        val encoded = encodePacketList(packet)

        val decoded = NativeLocalPacketsParser.decode(encoded)

        try {
            assertEquals(1, decoded.size)
            assertArrayEquals(packet, decoded.single())
            assertCleared(encoded)
        } finally {
            decoded.forEach { it.fill(0) }
            packet.fill(0)
        }
    }

    @Test
    fun acceptsAnEnvelopeAtTheExactCoreResultLimit() {
        val packetCount = 16
        val finalPacketBytes = maximumResultBytes - 1 - packetCount * opaque24LengthBytes -
            (packetCount - 1) * maximumPacketBytes
        val sourcePackets = Array(packetCount) { index ->
            ByteArray(if (index == packetCount - 1) finalPacketBytes else maximumPacketBytes) {
                (index + it).toByte()
            }
        }
        val encoded = encodePacketList(*sourcePackets)
        assertEquals(maximumResultBytes, encoded.size)

        val decoded = NativeLocalPacketsParser.decode(encoded)

        try {
            assertEquals(sourcePackets.size, decoded.size)
            sourcePackets.indices.forEach { index ->
                assertArrayEquals(sourcePackets[index], decoded[index])
            }
            assertCleared(encoded)
        } finally {
            decoded.forEach { it.fill(0) }
            sourcePackets.forEach { it.fill(0) }
        }
    }

    @Test
    fun rejectsNonMinimalAndTruncatedPacketCountsAndClearsThem() {
        val invalidCounts = listOf(
            byteArrayOf(0x40, 0x00),
            byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x40),
            byteArrayOf(0xc0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00),
            byteArrayOf(0x40),
            byteArrayOf(0x80.toByte(), 0x00, 0x00),
            byteArrayOf(0xc0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        )

        invalidCounts.forEach { encoded ->
            assertThrows(IllegalArgumentException::class.java) {
                NativeLocalPacketsParser.decode(encoded)
            }
            assertCleared(encoded)
        }
    }

    @Test
    fun rejectsCountsAboveTheCoreQueueLimit() {
        val encoded = byteArrayOf(0x40, 0x41)

        assertThrows(IllegalArgumentException::class.java) {
            NativeLocalPacketsParser.decode(encoded)
        }

        assertCleared(encoded)
    }

    @Test
    fun rejectsInvalidOpaque24LengthsAndTruncatedPackets() {
        val invalidPackets = listOf(
            byteArrayOf(0x01, 0x00, 0x00),
            byteArrayOf(0x01, 0x00, 0x00, 0x00),
            byteArrayOf(0x01, 0x01, 0x00, 0x00),
            byteArrayOf(0x01, 0x00, 0x00, 0x02, 0x45),
        )

        invalidPackets.forEach { encoded ->
            assertThrows(IllegalArgumentException::class.java) {
                NativeLocalPacketsParser.decode(encoded)
            }
            assertCleared(encoded)
        }
    }

    @Test
    fun rejectsOversizedAndTrailingResultsAndClearsThem() {
        val oversized = ByteArray(maximumResultBytes + 1).also { it[0] = 0x00 }
        val trailing = byteArrayOf(0x00, 0x7f)

        listOf(oversized, trailing).forEach { encoded ->
            assertThrows(IllegalArgumentException::class.java) {
                NativeLocalPacketsParser.decode(encoded)
            }
            assertCleared(encoded)
        }
    }

    @Test
    fun clearsPreviouslyDecodedPacketsWhenALaterPacketIsMalformed() {
        val encoded = byteArrayOf(
            0x02,
            0x00, 0x00, 0x02, 0x45, 0x00,
            0x00, 0x00, 0x02, 0x60,
        )
        var observedPacket: ByteArray? = null

        assertThrows(IllegalArgumentException::class.java) {
            NativeLocalPacketsParser.decode(encoded) { packet ->
                if (observedPacket == null) {
                    observedPacket = packet
                }
            }
        }

        assertArrayEquals(ByteArray(2), requireNotNull(observedPacket))
        assertCleared(encoded)
    }

    private fun assertCleared(value: ByteArray) {
        assertArrayEquals(ByteArray(value.size), value)
    }

    private fun encodePacketList(vararg packets: ByteArray): ByteArray {
        require(packets.size <= maximumPacketCount)
        val count = when (packets.size) {
            in 0..63 -> byteArrayOf(packets.size.toByte())
            else -> byteArrayOf(0x40, packets.size.toByte())
        }
        val encoded = ByteArray(count.size + packets.sumOf { opaque24LengthBytes + it.size })
        System.arraycopy(count, 0, encoded, 0, count.size)
        var offset = count.size
        packets.forEach { packet ->
            require(packet.isNotEmpty() && packet.size <= maximumPacketBytes)
            encoded[offset] = (packet.size ushr 16).toByte()
            encoded[offset + 1] = (packet.size ushr 8).toByte()
            encoded[offset + 2] = packet.size.toByte()
            offset += opaque24LengthBytes
            System.arraycopy(packet, 0, encoded, offset, packet.size)
            offset += packet.size
        }
        return encoded
    }

    private companion object {
        const val opaque24LengthBytes = 3
        const val maximumPacketBytes = 65_535
        const val maximumPacketCount = 64
        const val maximumResultBytes = 1024 * 1024
    }
}
