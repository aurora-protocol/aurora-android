package org.aurora.protocol.android.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object NativeLocalPacketsParser {
    fun decode(encoded: ByteArray): List<ByteArray> {
        require(encoded.isNotEmpty() && encoded.size <= maximumResultBytes) { "invalid local packet result size" }
        val packets = ArrayList<ByteArray>()
        try {
            val value = JSONObject(decodeUtf8(encoded))
            require(value.length() == 1 && value.has("packets_base64")) { "invalid local packet result fields" }
            val entries = value.get("packets_base64") as? JSONArray
                ?: throw IllegalArgumentException("invalid local packet result type")
            // Core's canonical result is an empty array when ingress produced no
            // immediate local packet (for example, forwarded DNS or a partial
            // fragment). Empty is success, not a terminal tunnel condition.
            require(entries.length() in 0..maximumPacketCount) { "invalid local packet result count" }
            packets.ensureCapacity(entries.length())
            for (index in 0 until entries.length()) {
                val text = entries.get(index) as? String
                    ?: throw IllegalArgumentException("invalid local packet encoding")
                val packet = decodeBase64(text)
                if (packet.isEmpty() || packet.size > maximumPacketBytes) {
                    packet.fill(0)
                    throw IllegalArgumentException("invalid local packet size")
                }
                packets += packet
            }
            return packets
        } catch (error: JSONException) {
            // Caught separately from RuntimeException: org.json.JSONException is a
            // RuntimeException in the test artifact but a checked Exception on device.
            packets.forEach { it.fill(0) }
            throw IllegalArgumentException("invalid Core local packet result", error)
        } catch (error: RuntimeException) {
            packets.forEach { it.fill(0) }
            throw IllegalArgumentException("invalid Core local packet result", error)
        }
    }

    private fun decodeBase64(value: String): ByteArray {
        require(value.isNotEmpty() && value.length <= maximumPacketBase64Characters) {
            "invalid local packet encoding"
        }
        val decoded = try {
            Base64.getDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("invalid local packet encoding", error)
        }
        var canonical: ByteArray? = null
        try {
            canonical = Base64.getEncoder().encode(decoded)
            require(canonical.size == value.length && canonical.indices.all { index ->
                canonical[index].toInt() and 0xff == value[index].code
            }) {
                "non-canonical local packet encoding"
            }
            return decoded
        } catch (error: RuntimeException) {
            decoded.fill(0)
            throw error
        } finally {
            canonical?.fill(0)
        }
    }

    private fun decodeUtf8(encoded: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString()
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("invalid local packet result encoding", error)
    }

    private const val maximumPacketBytes = 65535
    private const val maximumPacketBase64Characters = ((maximumPacketBytes + 2) / 3) * 4
    private const val maximumPacketCount = 64
    private const val maximumResultBytes = 2 * 1024 * 1024
}
