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
        var primaryFailure: Throwable? = null
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
                closeNativeSessionChecked(work.handle)
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
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            issuerResponse?.fill(0)
            var cleanupFailure: Throwable? = null
            try {
                work?.close()
            } catch (error: Throwable) {
                cleanupFailure = error
            }
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
                    try {
                        closeNativeSessionChecked(handle)
                    } catch (error: Throwable) {
                        cleanupFailure = combineFailures(cleanupFailure, error)
                    }
                }
            }
            cleanupFailure?.let { cleanup ->
                val primary = primaryFailure
                if (primary == null) {
                    throw cleanup
                }
                combineFailures(primary, cleanup)
            }
        }
    }

    override fun ingressLocalPacket(packet: ByteArray): List<ByteArray> {
        try {
            val handle = establishedHandle()
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
        val handle = synchronized(lock) {
            if (closed) {
                return
            }
            ++generation
            closed = true
            starting = false
            val current = if (activeHandle != 0L) activeHandle else pendingHandle
            activeHandle = 0L
            pendingHandle = 0L
            current
        }

        var failure: Throwable? = null
        try {
            (issuer as? CancellableIssuerExchange)?.cancel()
        } catch (error: Throwable) {
            failure = error
        }
        if (handle != 0L) {
            try {
                closeNativeSessionChecked(handle)
            } catch (error: Throwable) {
                failure = combineFailures(failure, error)
            }
        }
        failure?.let { throw it }
    }

    private fun closeNativeSessionChecked(handle: Long) {
        check(core.closeNativeSession(handle)) { "Core native session close rejected" }
    }

    private fun establishedHandle(): Long = synchronized(lock) {
        check(activeHandle != 0L) { "native session is not established" }
        activeHandle
    }

    private companion object {
        const val maximumIssuerResponseBytes = 1024 * 1024

        fun combineFailures(first: Throwable?, next: Throwable): Throwable {
            if (first == null) {
                return next
            }
            if (first !== next) {
                first.addSuppressed(next)
            }
            return first
        }
    }
}
