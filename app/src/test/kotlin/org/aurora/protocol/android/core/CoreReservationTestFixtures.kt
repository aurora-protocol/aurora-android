package org.aurora.protocol.android.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun encodedCoreReservation(
    provisioning: ByteArray = byteArrayOf(0x01, 0x02),
    spentHintKey: ByteArray = ByteArray(48) { it.toByte() },
    relayBucketId: ByteArray = ByteArray(16) { (it + 48).toByte() },
    expiry: Long = 123,
): ByteArray {
    require(provisioning.size <= 0xffffff)
    require(spentHintKey.size == 48)
    require(relayBucketId.size == 16)
    return ByteBuffer.allocate(3 + provisioning.size + spentHintKey.size + relayBucketId.size + Long.SIZE_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .put((provisioning.size ushr 16).toByte())
        .put((provisioning.size ushr 8).toByte())
        .put(provisioning.size.toByte())
        .put(provisioning)
        .put(spentHintKey)
        .put(relayBucketId)
        .putLong(expiry)
        .array()
}
