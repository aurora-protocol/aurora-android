package org.aurora.protocol.android.core

import java.security.MessageDigest

internal class ReservationStorageState(
    var reservation: CoreReservation? = null,
    var sourceDigest: ByteArray? = null,
    val history: MutableList<ReservationHistoryEntry> = mutableListOf(),
) : AutoCloseable {
    fun containsSpentHintKey(spentHintKey: ByteArray): Boolean = history.any {
        MessageDigest.isEqual(it.spentHintKey, spentHintKey)
    }

    fun prune(nowUnix: Long) {
        val iterator = history.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.accessHintExpiryUnix <= nowUnix) {
                entry.close()
                iterator.remove()
            }
        }
        if (history.isEmpty()) {
            sourceDigest?.fill(0)
            sourceDigest = null
        }
    }

    fun clearHistory() {
        sourceDigest?.fill(0)
        sourceDigest = null
        history.forEach { it.close() }
        history.clear()
    }

    fun takeReservation(): CoreReservation? {
        val result = reservation
        reservation = null
        return result
    }

    override fun close() {
        reservation?.close()
        reservation = null
        clearHistory()
    }
}

internal class ReservationHistoryEntry(
    val spentHintKey: ByteArray,
    var accessHintExpiryUnix: Long,
) : AutoCloseable {
    override fun close() {
        spentHintKey.fill(0)
    }
}
