package org.aurora.protocol.android.core

import java.util.Base64
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
        val value = JSONObject(String(encoded, Charsets.UTF_8))
        val fields = buildSet {
            val keys = value.keys()
            while (keys.hasNext()) {
                add(keys.next())
            }
        }
        require(fields == expectedFields) { "invalid reservation fields" }

        var provisioning: ByteArray? = null
        var spentHintKey: ByteArray? = null
        var relayBucketId: ByteArray? = null
        try {
            provisioning = decodeBase64(requireString(value, "provisioning_base64"))
            spentHintKey = decodeBase64(requireString(value, "spent_hint_key_base64"))
            relayBucketId = decodeBase64(requireString(value, "relay_bucket_id_base64"))
            require(provisioning.isNotEmpty()) { "empty provisioning" }
            require(spentHintKey.size == spentHintKeyLength) { "invalid spent hint key" }
            require(relayBucketId.size == relayBucketIdLength) { "invalid relay bucket identifier" }
            val expiry = requireInteger(value, "access_hint_expiry_unix")
            require(expiry > 0) { "invalid reservation expiry" }
            return CoreReservation(provisioning, spentHintKey, relayBucketId, expiry)
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

    private fun decodeBase64(value: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("invalid reservation encoding", error)
    }

    private const val spentHintKeyLength = 48
    private const val relayBucketIdLength = 16
}
