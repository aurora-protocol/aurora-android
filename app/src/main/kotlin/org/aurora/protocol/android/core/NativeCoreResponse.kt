package org.aurora.protocol.android.core

internal enum class CoreStatus(val wireValue: Int) {
    OK(0),
    CONFLICT(1),
    ERROR(2),
    ;

    companion object {
        fun fromWireValue(value: Int): CoreStatus = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("unknown Core status")
    }
}

internal class CoreResponse(
    val status: CoreStatus,
    val payload: ByteArray,
) : AutoCloseable {
    override fun close() {
        payload.fill(0)
    }
}

internal object NativeCoreResponse {
    fun decode(raw: ByteArray): CoreResponse = try {
        require(raw.isNotEmpty()) { "missing Core result" }
        val status = CoreStatus.fromWireValue(raw[0].toInt() and 0xff)
        val payload = raw.copyOfRange(1, raw.size)
        if (status != CoreStatus.OK && payload.isNotEmpty()) {
            payload.fill(0)
            throw IllegalArgumentException("unexpected Core error payload")
        }
        CoreResponse(status, payload)
    } finally {
        raw.fill(0)
    }
}
