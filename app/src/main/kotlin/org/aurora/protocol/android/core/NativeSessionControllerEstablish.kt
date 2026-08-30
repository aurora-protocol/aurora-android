package org.aurora.protocol.android.core

internal fun NativeSessionController.establishNativeSession(
    provisioning: ByteArray,
    beforeCoreCompletion: () -> Unit,
): Long {
    val ownGeneration = try {
        synchronized(lock) {
            check(!closed && !starting && pendingHandle == 0L && activeHandle == 0L) {
                "native session is unavailable"
            }
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
        require(
            issuerResponse.isNotEmpty() &&
                issuerResponse.size <= NativeSessionController.maximumIssuerResponseBytes,
        ) {
            "invalid issuer response"
        }
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
                    cleanupFailure = combineNativeSessionFailures(cleanupFailure, error)
                }
            }
        }
        cleanupFailure?.let { cleanup ->
            val primary = primaryFailure
            if (primary == null) {
                throw cleanup
            }
            combineNativeSessionFailures(primary, cleanup)
        }
    }
}
