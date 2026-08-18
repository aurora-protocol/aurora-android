package org.aurora.protocol.android.core

import java.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object NativeLocalPacketsParser {
    fun decode(encoded: ByteArray): List<ByteArray> {
        require(encoded.isNotEmpty() && encoded.size <= maximumResultBytes) { "invalid local packet result size" }
        val value = JSONObject(String(encoded, Charsets.UTF_8))
        require(value.length() == 1 && value.has("packets_base64")) { "invalid local packet result fields" }
        val entries = value.get("packets_base64") as? JSONArray
            ?: throw IllegalArgumentException("invalid local packet result type")
        require(entries.length() in 1..maximumPacketCount) { "invalid local packet result count" }

        val packets = ArrayList<ByteArray>(entries.length())
        try {
            for (index in 0 until entries.length()) {
                val text = entries.get(index) as? String
                    ?: throw IllegalArgumentException("invalid local packet encoding")
                val packet = try {
                    Base64.getDecoder().decode(text)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("invalid local packet encoding", error)
                }
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

    private const val maximumPacketBytes = 65535
    private const val maximumPacketCount = 64
    private const val maximumResultBytes = 2 * 1024 * 1024
}
