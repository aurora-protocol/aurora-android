package org.aurora.protocol.android.core

import java.net.URI
import java.net.URL
import java.util.Base64
import org.json.JSONException
import org.json.JSONObject

internal class NativeIssuerWork(
    val handle: Long,
    val issuerUrl: URL,
    val issuerCarrierPath: String,
    val requestBody: ByteArray,
) : AutoCloseable {
    override fun close() {
        requestBody.fill(0)
    }
}

internal object NativeIssuerWorkParser {
    private val expectedFields = setOf(
        "handle",
        "issuer_url",
        "issuer_carrier_path",
        "request_body_base64",
    )

    fun decode(encoded: ByteArray): NativeIssuerWork {
        require(encoded.isNotEmpty() && encoded.size <= maximumIssuerWorkBytes) { "invalid issuer work size" }
        val value = JSONObject(String(encoded, Charsets.UTF_8))
        val fields = buildSet {
            val keys = value.keys()
            while (keys.hasNext()) {
                add(keys.next())
            }
        }
        require(fields == expectedFields) { "invalid issuer work fields" }

        var requestBody: ByteArray? = null
        try {
            val handle = requirePositiveInteger(value, "handle")
            val issuerUrl = parseIssuerUrl(requireString(value, "issuer_url"))
            val carrierPath = parseCarrierPath(requireString(value, "issuer_carrier_path"))
            requestBody = decodeBase64(requireString(value, "request_body_base64"))
            require(requestBody.isNotEmpty() && requestBody.size <= maximumRequestBytes) { "invalid issuer request body" }
            return NativeIssuerWork(handle, issuerUrl, carrierPath, requestBody)
        } catch (error: JSONException) {
            // Caught separately from RuntimeException: org.json.JSONException is a
            // RuntimeException in the test artifact but a checked Exception on device.
            requestBody?.fill(0)
            throw IllegalArgumentException("invalid Core issuer work", error)
        } catch (error: RuntimeException) {
            requestBody?.fill(0)
            throw IllegalArgumentException("invalid Core issuer work", error)
        }
    }

    private fun parseIssuerUrl(value: String): URL {
        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true)) { "issuer URL is not HTTPS" }
        require(uri.host != null && uri.userInfo == null) { "issuer URL authority is invalid" }
        require(uri.port in -1..65535) { "issuer URL port is invalid" }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "issuer URL must be an origin" }
        require(uri.rawQuery == null && uri.rawFragment == null) { "issuer URL contains unsupported components" }
        return uri.toURL()
    }

    private fun parseCarrierPath(value: String): String {
        require(value.isNotEmpty() && value.length <= maximumCarrierPathCharacters) { "issuer carrier path is invalid" }
        require(value.startsWith("/") && !value.startsWith("//")) { "issuer carrier path is not absolute" }
        require(!value.contains('?') && !value.contains('#') && !value.contains('\\')) {
            "issuer carrier path contains unsupported components"
        }
        val uri = URI(null, null, value, null)
        require(uri.rawPath == value && uri.normalize().rawPath == value) { "issuer carrier path is not normalized" }
        return value
    }

    private fun requireString(value: JSONObject, field: String): String {
        return value.get(field) as? String ?: throw IllegalArgumentException("invalid issuer work field type")
    }

    private fun requirePositiveInteger(value: JSONObject, field: String): Long {
        return when (val raw = value.get(field)) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> throw IllegalArgumentException("invalid issuer work field type")
        }.also { require(it > 0) { "invalid issuer work handle" } }
    }

    private fun decodeBase64(value: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("invalid issuer request encoding", error)
    }

    private const val maximumRequestBytes = 8 * 1024
    private const val maximumCarrierPathCharacters = 8 * 1024
    private const val maximumIssuerWorkBytes = 32 * 1024
}
