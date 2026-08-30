package org.aurora.protocol.android

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal fun newVpnTeardownExecutor(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "aurora-vpn-teardown")
}

internal fun newVpnRejectionExecutor(): Executor = Executor { runnable ->
    Thread(runnable, "aurora-vpn-teardown-recovery").start()
}

internal fun combineVpnLifecycleFailures(first: Throwable, next: Throwable): Throwable {
    if (first !== next) {
        first.addSuppressed(next)
    }
    return first
}
