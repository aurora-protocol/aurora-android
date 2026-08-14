package org.aurora.protocol.android.core

internal object NativeCoreJni {
    private const val maximumTrustBytes = 64 * 1024
    private const val maximumReservationInputBytes = (16 * 1024 * 1024) + 4 + 1 + (64 * 48)

    init {
        System.loadLibrary("auroracore")
        System.loadLibrary("aurora_android_jni")
    }

    fun reserveNativeProvisioning(request: ByteArray, issuedAtUnix: Long): CoreResponse {
        require(request.isNotEmpty() && request.size <= maximumReservationInputBytes) { "invalid reservation request" }
        require(issuedAtUnix > 0) { "invalid reservation time" }
        val raw = nativeCall(CoreOperation.RESERVE_NATIVE_PROVISIONING_JSON.wireValue, request, issuedAtUnix)
            ?: throw IllegalStateException("Core reservation call failed")
        return NativeCoreResponse.decode(raw)
    }

    fun configureNativeProvisioningTrust(encoded: ByteArray): Boolean {
        require(encoded.isNotEmpty() && encoded.size <= maximumTrustBytes) { "invalid native trust" }
        val raw = nativeCall(CoreOperation.CONFIGURE_NATIVE_PROVISIONING_TRUST.wireValue, encoded, 0)
            ?: return false
        return try {
            NativeCoreResponse.decode(raw).use { response ->
                response.status == CoreStatus.OK && response.payload.isEmpty()
            }
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private external fun nativeCall(operation: Int, input: ByteArray, argument: Long): ByteArray?
}
