package org.aurora.protocol.android.core

internal class AuroraReservationRepository(
    private val client: NativeProvisioningReservationClient,
    private val storage: ReservationStore,
) {
    fun reserveAndPersist(request: ByteArray, issuedAtUnix: Long) {
        storage.save(client.reserve(request, issuedAtUnix))
    }

    fun load(): CoreReservation? = storage.load()

    fun consume(): CoreReservation? {
        val reservation = storage.load() ?: return null
        try {
            storage.clear()
            return reservation
        } catch (error: Exception) {
            reservation.close()
            throw error
        }
    }

    fun clear() {
        storage.clear()
    }
}
