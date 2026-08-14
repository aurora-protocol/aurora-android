package org.aurora.protocol.android.core

internal class NativeSessionController(
    private val core: NativeSessionCore = NativeCoreJni,
    private val issuer: IssuerExchange = HttpsIssuerExchange(),
) : AutoCloseable {
    private val lock = Any()
    private var generation = 0L
    private var pendingHandle = 0L
    private var activeHandle = 0L
    private var starting = false

    val isEstablished: Boolean
        get() = synchronized(lock) { activeHandle != 0L }

    fun establish(provisioning: ByteArray): Long {
        val ownGeneration = synchronized(lock) {
            check(!starting && pendingHandle == 0L && activeHandle == 0L) { "native session already exists" }
            starting = true
            ++generation
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
            synchronized(lock) {
                check(generation == ownGeneration && starting) { "native session was cancelled" }
                pendingHandle = work.handle
            }
            issuerResponse = issuer.exchange(work)
            require(issuerResponse.isNotEmpty() && issuerResponse.size <= maximumIssuerResponseBytes) { "invalid issuer response" }
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
                synchronized(lock) {
                    if (generation == ownGeneration) {
                        pendingHandle = 0L
                        starting = false
                    }
                }
                if (handle != 0L) {
                    core.closeNativeSession(handle)
                }
            }
        }
    }

    fun ingressLocalPacket(packet: ByteArray): List<ByteArray> {
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

    fun nextLocalPacket(): ByteArray = core.nextLocalPacket(establishedHandle())

    override fun close() {
        val handle = synchronized(lock) {
            ++generation
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
