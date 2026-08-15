package org.aurora.protocol.android.core

internal interface NativePacketSession : AutoCloseable {
    fun ingressLocalPacket(packet: ByteArray): List<ByteArray>
    fun nextLocalPacket(): ByteArray
}

internal class NativeSessionController(
    private val core: NativeSessionCore = NativeCoreJni,
    private val issuer: IssuerExchange = HttpsIssuerExchange(),
) : NativePacketSession {
    private val lock = Any()
    private var generation = 0L
    private var pendingHandle = 0L
    private var activeHandle = 0L
    private var starting = false
    private var closed = false

    val isEstablished: Boolean
        get() = synchronized(lock) { activeHandle != 0L }

    fun establish(provisioning: ByteArray, beforeCoreCompletion: () -> Unit = {}): Long {
        val ownGeneration = try {
            synchronized(lock) {
                check(!closed && !starting && pendingHandle == 0L && activeHandle == 0L) { "native session is unavailable" }
                starting = true
                ++generation
            }
        } catch (error: IllegalStateException) {
            provisioning.fill(0)
            throw error
        }
        var work: NativeIssuerWork? = null
        var issuerResponse: ByteArray? = null
        var established = false
        try {
            work = try {
                core.beginNativeSession(provisioning).use { response ->
                    check(response.status == CoreStatus.OK) { "Core native session start rejected" }
                    NativeIssuerWorkParser.decode(response.payload)
                }
            } finally {
                provisioning.fill(0)
            }
            val closeReturnedHandle = synchronized(lock) {
                if (generation != ownGeneration || !starting) {
                    true
                } else {
                    pendingHandle = work.handle
                    false
                }
            }
            if (closeReturnedHandle) {
                core.closeNativeSession(work.handle)
                throw IllegalStateException("native session was cancelled")
            }
            issuerResponse = issuer.exchange(work)
            require(issuerResponse.isNotEmpty() && issuerResponse.size <= maximumIssuerResponseBytes) { "invalid issuer response" }
            beforeCoreCompletion()
            check(core.completeNativeSession(work.handle, issuerResponse)) { "Core native session completion rejected" }
            established = synchronized(lock) {
                if (generation != ownGeneration || !starting || pendingHandle != work.handle) {
                    false
                } else {
                    pendingHandle = 0L
                    activeHandle = work.handle
                    starting = false
                    true
                }
            }
            check(established) { "native session was cancelled" }
            return work.handle
        } finally {
            issuerResponse?.fill(0)
            work?.close()
            if (!established) {
                val handle = work?.handle ?: 0L
                val shouldClose = synchronized(lock) {
                    if (generation == ownGeneration) {
                        if (pendingHandle == handle) {
                            pendingHandle = 0L
                        }
                        starting = false
                        true
                    } else {
                        false
                    }
                }
                if (shouldClose && handle != 0L) {
                    core.closeNativeSession(handle)
                }
            }
        }
    }

    override fun ingressLocalPacket(packet: ByteArray): List<ByteArray> {
        val handle = establishedHandle()
        try {
            core.ingressLocalPacket(handle, packet).use { response ->
                check(response.status == CoreStatus.OK) { "Core local packet ingress rejected" }
                return NativeLocalPacketsParser.decode(response.payload)
            }
        } finally {
            packet.fill(0)
        }
    }

    override fun nextLocalPacket(): ByteArray = core.nextLocalPacket(establishedHandle())

    override fun close() {
        (issuer as? CancellableIssuerExchange)?.cancel()
        val handle = synchronized(lock) {
            ++generation
            closed = true
            starting = false
            val current = if (activeHandle != 0L) activeHandle else pendingHandle
            activeHandle = 0L
            pendingHandle = 0L
            current
        }
        if (handle != 0L) {
            core.closeNativeSession(handle)
        }
    }

    private fun establishedHandle(): Long = synchronized(lock) {
        check(activeHandle != 0L) { "native session is not established" }
        activeHandle
    }

    private companion object {
        const val maximumIssuerResponseBytes = 1024 * 1024
    }
}
