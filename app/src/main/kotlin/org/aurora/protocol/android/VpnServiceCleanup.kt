package org.aurora.protocol.android

internal fun runVpnServiceRequest(request: () -> Unit): RuntimeException? = try {
    request()
    null
} catch (error: RuntimeException) {
    error
}

internal fun collectCleanupFailures(vararg steps: () -> Unit): Throwable? {
    var failure: Throwable? = null
    steps.forEach { step ->
        try {
            step()
        } catch (error: Throwable) {
            val first = failure
            if (first == null) {
                failure = error
            } else if (first !== error) {
                first.addSuppressed(error)
            }
        }
    }
    return failure
}
