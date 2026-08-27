package org.aurora.protocol.android.core

/** Decodes Core's private binary local-packet-list ABI. */
internal object NativeLocalPacketsParser {
    /** Takes ownership of [encoded] and clears it before returning or throwing. */
    fun decode(encoded: ByteArray): List<ByteArray> = decode(encoded, null)

    /** Test seam for observing packet buffers that must be cleared after a later failure. */
    internal fun decode(
        encoded: ByteArray,
        decodedPacketObserver: ((ByteArray) -> Unit)?,
    ): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        try {
            require(encoded.isNotEmpty() && encoded.size <= maximumResultBytes) {
                "invalid local packet result size"
            }
            val reader = PacketListReader(encoded)
            val count = reader.readCanonicalVarint()
            require(count <= maximumPacketCount.toLong()) { "invalid local packet result count" }
            packets.ensureCapacity(count.toInt())

            var aggregatePacketBytes = 0
            repeat(count.toInt()) {
                val packetLength = reader.readOpaque24Length()
                require(packetLength in 1..maximumPacketBytes) { "invalid local packet size" }
                require(aggregatePacketBytes <= maximumResultBytes - packetLength) {
                    "local packet result exceeds size limit"
                }
                aggregatePacketBytes += packetLength

                val packet = reader.readPacket(packetLength)
                var retained = false
                try {
                    packets += packet
                    retained = true
                    decodedPacketObserver?.invoke(packet)
                } finally {
                    if (!retained) {
                        packet.fill(0)
                    }
                }
            }
            require(reader.isExhausted()) { "local packet result has trailing bytes" }
            return packets
        } catch (error: Throwable) {
            packets.forEach { it.fill(0) }
            packets.clear()
            throw error
        } finally {
            encoded.fill(0)
        }
    }

    private class PacketListReader(private val encoded: ByteArray) {
        private var offset = 0

        fun readCanonicalVarint(): Long {
            requireRemaining(1, "local packet result count is truncated")
            val first = encoded[offset].toInt() and 0xff
            val encodedBytes = 1 shl (first ushr 6)
            requireRemaining(encodedBytes, "local packet result count is truncated")

            var value = (first and 0x3f).toLong()
            repeat(encodedBytes - 1) { index ->
                value = (value shl Byte.SIZE_BITS) or
                    (encoded[offset + index + 1].toInt() and 0xff).toLong()
            }
            val minimumValue = when (encodedBytes) {
                1 -> 0L
                2 -> 64L
                4 -> 16_384L
                8 -> 1_073_741_824L
                else -> error("unreachable QUIC varint length")
            }
            require(value >= minimumValue) { "local packet result count is not canonical" }
            offset += encodedBytes
            return value
        }

        fun readOpaque24Length(): Int {
            requireRemaining(opaque24LengthBytes, "local packet length is truncated")
            val length = ((encoded[offset].toInt() and 0xff) shl 16) or
                ((encoded[offset + 1].toInt() and 0xff) shl 8) or
                (encoded[offset + 2].toInt() and 0xff)
            offset += opaque24LengthBytes
            return length
        }

        fun readPacket(length: Int): ByteArray {
            requireRemaining(length, "local packet is truncated")
            val packet = ByteArray(length)
            System.arraycopy(encoded, offset, packet, 0, length)
            offset += length
            return packet
        }

        fun isExhausted(): Boolean = offset == encoded.size

        private fun requireRemaining(length: Int, message: String) {
            require(length >= 0 && offset <= encoded.size - length) { message }
        }
    }

    private const val opaque24LengthBytes = 3
    private const val maximumPacketBytes = 65_535
    private const val maximumPacketCount = 64
    private const val maximumResultBytes = 1024 * 1024
}
