package org.aurora.protocol.android.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import org.json.JSONException
import org.json.JSONObject

internal class CoreReservation(
    val provisioning: ByteArray,
    val spentHintKey: ByteArray,
    val relayBucketId: ByteArray,
    val accessHintExpiryUnix: Long,
) : AutoCloseable {
    override fun close() {
        provisioning.fill(0)
        spentHintKey.fill(0)
        relayBucketId.fill(0)
    }
}

internal object CoreReservationParser {
    private val expectedFields = setOf(
        "provisioning_base64",
        "spent_hint_key_base64",
        "relay_bucket_id_base64",
        "access_hint_expiry_unix",
    )

    fun decode(encoded: ByteArray): CoreReservation {
        require(encoded.isNotEmpty() && encoded.size <= maximumReservationResultBytes) {
            "invalid reservation result size"
        }
        var provisioning: ByteArray? = null
        var spentHintKey: ByteArray? = null
        var relayBucketId: ByteArray? = null
        try {
            val value = JSONObject(decodeUtf8(encoded))
            val fields = buildSet {
                val keys = value.keys()
                while (keys.hasNext()) {
                    add(keys.next())
                }
            }
            require(fields == expectedFields) { "invalid reservation fields" }

            provisioning = decodeBase64(requireString(value, "provisioning_base64"), maximumProvisioningBytes)
            spentHintKey = decodeBase64(requireString(value, "spent_hint_key_base64"), spentHintKeyLength)
            relayBucketId = decodeBase64(requireString(value, "relay_bucket_id_base64"), relayBucketIdLength)
            require(provisioning.isNotEmpty() && provisioning.size <= maximumProvisioningBytes) { "invalid provisioning" }
            require(spentHintKey.size == spentHintKeyLength) { "invalid spent hint key" }
            require(relayBucketId.size == relayBucketIdLength) { "invalid relay bucket identifier" }
            val expiry = requireInteger(value, "access_hint_expiry_unix")
            require(expiry > 0) { "invalid reservation expiry" }
            return CoreReservation(provisioning, spentHintKey, relayBucketId, expiry)
        } catch (error: JSONException) {
            // JSONException is a RuntimeException in the org.json test artifact but a
            // checked Exception on device, so it must be caught explicitly for the
            // scrubbing below to run identically in both environments.
            provisioning?.fill(0)
            spentHintKey?.fill(0)
            relayBucketId?.fill(0)
            throw IllegalArgumentException("invalid Core reservation", error)
        } catch (error: RuntimeException) {
            provisioning?.fill(0)
            spentHintKey?.fill(0)
            relayBucketId?.fill(0)
            throw IllegalArgumentException("invalid Core reservation", error)
        }
    }

    private fun requireString(value: JSONObject, field: String): String {
        return value.get(field) as? String ?: throw IllegalArgumentException("invalid reservation field type")
    }

    private fun requireInteger(value: JSONObject, field: String): Long {
        return when (val raw = value.get(field)) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> throw IllegalArgumentException("invalid reservation field type")
        }
    }

    private fun decodeBase64(value: String, maximumDecodedBytes: Int): ByteArray {
        val maximumEncodedCharacters = ((maximumDecodedBytes + 2) / 3) * 4
        require(value.isNotEmpty() && value.length <= maximumEncodedCharacters) { "invalid reservation encoding" }
        val decoded = try {
            Base64.getDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("invalid reservation encoding", error)
        }
        var canonical: ByteArray? = null
        try {
            require(decoded.size <= maximumDecodedBytes) { "invalid reservation encoding" }
            canonical = Base64.getEncoder().encode(decoded)
            require(canonical.size == value.length && canonical.indices.all { index ->
                canonical[index].toInt() and 0xff == value[index].code
            }) {
                "non-canonical reservation encoding"
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
        throw IllegalArgumentException("invalid reservation result encoding", error)
    }

    private const val maximumProvisioningBytes = 16 * 1024 * 1024
    private const val maximumReservationResultBytes = ((maximumProvisioningBytes + 2) / 3) * 4 + 384
    private const val spentHintKeyLength = 48
    private const val relayBucketIdLength = 16
}
