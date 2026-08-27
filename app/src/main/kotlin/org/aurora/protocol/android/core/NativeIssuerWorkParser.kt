package org.aurora.protocol.android.core

import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
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
        var requestBody: ByteArray? = null
        try {
            val value = JSONObject(decodeUtf8(encoded))
            val fields = buildSet {
                val keys = value.keys()
                while (keys.hasNext()) {
                    add(keys.next())
                }
            }
            require(fields == expectedFields) { "invalid issuer work fields" }

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
        } catch (error: Exception) {
            requestBody?.fill(0)
            throw IllegalArgumentException("invalid Core issuer work", error)
        }
    }

    private fun parseIssuerUrl(value: String): URL {
        require(value.utf8Size() in 1..maximumIssuerComponentBytes) { "issuer URL length is invalid" }
        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true)) { "issuer URL is not HTTPS" }
        require(uri.host != null && uri.userInfo == null) { "issuer URL authority is invalid" }
        require(uri.rawAuthority != null && !(uri.port == -1 && uri.rawAuthority.endsWith(':'))) {
            "issuer URL authority is invalid"
        }
        require(uri.port == -1 || uri.port in 1..65_535) { "issuer URL port is invalid" }
        require(uri.rawPath.isNullOrEmpty()) { "issuer URL must be an origin" }
        require(uri.rawQuery == null && uri.rawFragment == null) { "issuer URL contains unsupported components" }
        return uri.toURL()
    }

    private fun parseCarrierPath(value: String): String {
        require(value.utf8Size() in 2..maximumIssuerComponentBytes) { "issuer carrier path is invalid" }
        require(value.startsWith("/") && !value.contains("//") && !value.endsWith('/')) {
            "issuer carrier path is not canonical"
        }
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

    private fun decodeBase64(value: String): ByteArray {
        require(value.isNotEmpty() && value.length <= maximumRequestBase64Characters) {
            "invalid issuer request encoding"
        }
        val decoded = try {
            Base64.getDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("invalid issuer request encoding", error)
        }
        try {
            require(Base64.getEncoder().encodeToString(decoded) == value) { "non-canonical issuer request encoding" }
            return decoded
        } catch (error: RuntimeException) {
            decoded.fill(0)
            throw error
        }
    }

    private fun decodeUtf8(encoded: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString()
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("invalid issuer work encoding", error)
    }

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

    private const val maximumRequestBytes = 8 * 1024
    private const val maximumRequestBase64Characters = ((maximumRequestBytes + 2) / 3) * 4
    private const val maximumIssuerComponentBytes = 2 * 1024
    private const val maximumIssuerWorkBytes = 32 * 1024
}
