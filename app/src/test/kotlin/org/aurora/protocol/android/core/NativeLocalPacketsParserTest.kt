package org.aurora.protocol.android.core

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeLocalPacketsParserTest {
    @Test
    fun decodesBoundedCorePackets() {
        val first = byteArrayOf(0x45, 0x00, 0x00, 0x14)
        val second = byteArrayOf(0x60, 0x00, 0x00, 0x00)
        val payload = """
            {"packets_base64":["${Base64.getEncoder().encodeToString(first)}","${Base64.getEncoder().encodeToString(second)}"]}
        """.trimIndent().toByteArray()

        val packets = NativeLocalPacketsParser.decode(payload)
        try {
            assertEquals(2, packets.size)
            assertArrayEquals(first, packets[0])
            assertArrayEquals(second, packets[1])
        } finally {
            packets.forEach { it.fill(0) }
        }
    }

    @Test
    fun rejectsEmptyAndOversizedPacketLists() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeLocalPacketsParser.decode("{\"packets_base64\":[]}".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeLocalPacketsParser.decode("{\"packets_base64\":[\"\"]}".toByteArray())
        }
        val entries = List(65) { "AQ==" }.joinToString(separator = "\",\"")
        assertThrows(IllegalArgumentException::class.java) {
            NativeLocalPacketsParser.decode("{\"packets_base64\":[\"$entries\"]}".toByteArray())
        }
    }
}
