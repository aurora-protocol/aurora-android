package org.aurora.protocol.android.core

internal interface NativePacketSession : AutoCloseable {
    fun ingressLocalPacket(packet: ByteArray): List<ByteArray>

    fun nextLocalPacket(): ByteArray
}

internal class NativeSessionController(
    internal val core: NativeSessionCore = NativeCoreJni,
    internal val issuer: IssuerExchange = HttpsIssuerExchange(),
) : NativePacketSession {
    internal val lock = Any()
    internal var generation = 0L
    internal var pendingHandle = 0L
    internal var activeHandle = 0L
    internal var starting = false
    internal var closed = false

    val isEstablished: Boolean
        get() = synchronized(lock) { activeHandle != 0L }

    fun establish(provisioning: ByteArray, beforeCoreCompletion: () -> Unit = {}): Long =
        establishNativeSession(provisioning, beforeCoreCompletion)

    override fun ingressLocalPacket(packet: ByteArray): List<ByteArray> {
        try {
            val handle = establishedHandle()
            core.ingressLocalPacket(handle, packet).use { response ->
                return when (response.status) {
                    CoreStatus.OK -> NativeLocalPacketsParser.decode(response.takePayload())
                    CoreStatus.CONFLICT -> {
                        check(response.payload.isEmpty()) { "Core local packet ingress conflict included a payload" }
                        emptyList()
                    }
                    CoreStatus.ERROR -> error("Core local packet ingress rejected")
                }
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
                failure = combineNativeSessionFailures(failure, error)
            }
        }
        failure?.let { throw it }
    }

    internal fun closeNativeSessionChecked(handle: Long) {
        check(core.closeNativeSession(handle)) { "Core native session close rejected" }
    }

    internal fun establishedHandle(): Long = synchronized(lock) {
        check(activeHandle != 0L) { "native session is not established" }
        activeHandle
    }

    internal companion object {
        const val maximumIssuerResponseBytes = 1024 * 1024
    }
}

internal fun combineNativeSessionFailures(first: Throwable?, next: Throwable): Throwable {
    if (first == null) {
        return next
    }
    if (first !== next) {
        first.addSuppressed(next)
    }
    return first
}
