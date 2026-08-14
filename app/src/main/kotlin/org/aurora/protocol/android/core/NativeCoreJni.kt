package org.aurora.protocol.android.core

internal object NativeCoreJni {
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

    private external fun nativeCall(operation: Int, input: ByteArray, argument: Long): ByteArray?
}
