package org.aurora.protocol.android.core

internal class NativeProvisioningReservationClient(
    private val reserveCore: (ByteArray, Long) -> CoreResponse,
) {
    fun reserve(request: ByteArray, issuedAtUnix: Long): CoreReservation {
        require(request.isNotEmpty() && request.size <= maximumReservationInputBytes) { "invalid reservation request" }
        require(issuedAtUnix > 0) { "invalid reservation time" }
        reserveCore(request, issuedAtUnix).use { response ->
            if (response.status != CoreStatus.OK) {
                throw IllegalStateException("Core reservation failed")
            }
            return CoreReservationParser.decode(response.takePayload())
        }
    }

    companion object {
        private const val maximumReservationInputBytes = (16 * 1024 * 1024) + 4 + 1 + (64 * 48)

        fun production(): NativeProvisioningReservationClient {
            return NativeProvisioningReservationClient(NativeCoreJni::reserveNativeProvisioning)
        }
    }
}
