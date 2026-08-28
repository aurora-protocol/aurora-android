package org.aurora.protocol.android.core

internal interface NativeSessionCore {
    fun beginNativeSession(provisioning: ByteArray): CoreResponse
    fun completeNativeSession(handle: Long, issuerResponse: ByteArray): Boolean
    fun ingressLocalPacket(handle: Long, packet: ByteArray): CoreResponse
    fun nextLocalPacket(handle: Long): ByteArray
    fun closeNativeSession(handle: Long): Boolean
}

internal object NativeCoreJni : NativeSessionCore {
    private const val maximumTrustBytes = 64 * 1024
    private const val maximumNativeProvisioningBytes = 1024 * 1024
    private const val maximumReservationInputBytes = (16 * 1024 * 1024) + 4 + 1 + (64 * 48)
    private const val maximumIssuerResponseBytes = 1024 * 1024
    private const val maximumLocalPacketBytes = 65535

    init {
        System.loadLibrary("auroracore")
        System.loadLibrary("aurora_android_jni")
    }

    fun reserveNativeProvisioning(request: ByteArray, issuedAtUnix: Long): CoreResponse {
        require(request.isNotEmpty() && request.size <= maximumReservationInputBytes) { "invalid reservation request" }
        require(issuedAtUnix > 0) { "invalid reservation time" }
        val raw = nativeCall(CoreOperation.RESERVE_NATIVE_PROVISIONING.wireValue, request, issuedAtUnix)
            ?: throw IllegalStateException("Core reservation call failed")
        return NativeCoreResponse.decode(raw)
    }

    override fun beginNativeSession(provisioning: ByteArray): CoreResponse {
        require(provisioning.isNotEmpty() && provisioning.size <= maximumNativeProvisioningBytes) {
            "invalid native provisioning"
        }
        val raw = nativeCall(CoreOperation.BEGIN_NATIVE_SESSION_JSON.wireValue, provisioning, 0)
            ?: throw IllegalStateException("Core native session start failed")
        return NativeCoreResponse.decode(raw)
    }

    override fun completeNativeSession(handle: Long, issuerResponse: ByteArray): Boolean {
        require(handle > 0) { "invalid native session handle" }
        require(issuerResponse.isNotEmpty() && issuerResponse.size <= maximumIssuerResponseBytes) { "invalid issuer response" }
        return callsWithEmptySuccess(CoreOperation.COMPLETE_NATIVE_SESSION_RAW, issuerResponse, handle)
    }

    override fun ingressLocalPacket(handle: Long, packet: ByteArray): CoreResponse {
        require(handle > 0) { "invalid native session handle" }
        require(packet.isNotEmpty() && packet.size <= maximumLocalPacketBytes) { "invalid local packet" }
        val raw = nativeCall(CoreOperation.INGRESS_LOCAL_PACKET.wireValue, packet, handle)
            ?: throw IllegalStateException("Core local packet ingress failed")
        return NativeCoreResponse.decode(raw)
    }

    override fun nextLocalPacket(handle: Long): ByteArray {
        require(handle > 0) { "invalid native session handle" }
        val raw = nativeCall(CoreOperation.NEXT_LOCAL_PACKET.wireValue, ByteArray(0), handle)
            ?: throw IllegalStateException("Core next local packet failed")
        NativeCoreResponse.decode(raw).use { response ->
            check(response.status == CoreStatus.OK) { "Core next local packet rejected" }
            val packet = response.takePayload()
            try {
                check(packet.isNotEmpty() && packet.size <= maximumLocalPacketBytes) { "invalid Core local packet" }
                return packet
            } catch (error: IllegalStateException) {
                packet.fill(0)
                throw error
            }
        }
    }

    override fun closeNativeSession(handle: Long): Boolean {
        require(handle > 0) { "invalid native session handle" }
        return callsWithEmptySuccess(CoreOperation.CLOSE_NATIVE_SESSION, ByteArray(0), handle)
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

    private fun callsWithEmptySuccess(operation: CoreOperation, input: ByteArray, argument: Long): Boolean {
        val raw = nativeCall(operation.wireValue, input, argument) ?: return false
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
