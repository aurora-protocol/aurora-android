package org.aurora.protocol.android.core

internal class AuroraReservationRepository(
    private val client: NativeProvisioningReservationClient,
    private val storage: ReservationStore,
) {
    fun reserveAndPersist(request: ByteArray, issuedAtUnix: Long) {
        storage.save(client.reserve(request, issuedAtUnix))
    }

    fun load(): CoreReservation? = storage.load()

    fun clear() {
        storage.clear()
    }
}
