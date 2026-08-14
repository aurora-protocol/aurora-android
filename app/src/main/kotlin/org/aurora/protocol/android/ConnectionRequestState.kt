package org.aurora.protocol.android

internal class ConnectionRequestState {
    var importInProgress: Boolean = false
        private set
    var connectRequested: Boolean = false
        private set

    fun beginImport(): Boolean {
        if (importInProgress || connectRequested) {
            return false
        }
        importInProgress = true
        return true
    }

    fun completeImport() {
        importInProgress = false
    }

    fun beginConnectionRequest(): Boolean {
        if (importInProgress || connectRequested) {
            return false
        }
        connectRequested = true
        return true
    }

    fun cancelConnectionRequest() {
        connectRequested = false
    }

    fun consumeConnectionRequest(): Boolean {
        if (!connectRequested) {
            return false
        }
        connectRequested = false
        return true
    }
}
