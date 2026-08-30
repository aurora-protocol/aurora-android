package org.aurora.protocol.android.core

import android.content.Context

internal class AndroidKeystoreReservationStore(context: Context) : ReservationStore {
    private val cipher = AndroidKeystoreReservationCipher()
    private val store = EncryptedReservationStore(
        blobs = AndroidReservationBlobStore(context),
        cipher = cipher,
    )

    override fun save(
        reservation: CoreReservation,
        sourceDigest: ByteArray,
        nowUnix: Long,
        callerSpentHintKeys: List<ByteArray>,
    ) {
        store.save(reservation, sourceDigest, nowUnix, callerSpentHintKeys)
    }

    override fun spentHintKeys(sourceDigest: ByteArray, nowUnix: Long): List<ByteArray> =
        store.spentHintKeys(sourceDigest, nowUnix)

    override fun load(): CoreReservation? = store.load()

    override fun consume(nowUnix: Long): ReservationConsumption = store.consume(nowUnix)

    override fun clear() = store.clear()

    override fun purge() {
        store.purge()
        cipher.clearKey()
    }
}
