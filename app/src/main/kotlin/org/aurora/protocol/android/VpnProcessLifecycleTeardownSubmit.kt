package org.aurora.protocol.android

internal fun VpnProcessLifecycle.submitTeardown(teardown: ActiveTeardown, resource: AutoCloseable?) {
    val cleanup = Runnable { completeTeardown(teardown, resource) }
    try {
        teardownExecutor.execute(cleanup)
    } catch (submissionFailure: Throwable) {
        var reportedFailure = submissionFailure
        val replacement = try {
            teardownExecutorFactory()
        } catch (replacementFailure: Throwable) {
            reportedFailure = combineVpnLifecycleFailures(reportedFailure, replacementFailure)
            null
        }
        if (replacement != null) {
            teardownExecutor = replacement
            try {
                replacement.execute(vpnLifecycleReportingCleanup(this, reportedFailure, cleanup))
                return
            } catch (replacementFailure: Throwable) {
                reportedFailure = combineVpnLifecycleFailures(reportedFailure, replacementFailure)
            }
        }
        try {
            rejectionExecutor.execute(vpnLifecycleReportingCleanup(this, reportedFailure, cleanup))
        } catch (rejectionFailure: Throwable) {
            reportedFailure = combineVpnLifecycleFailures(reportedFailure, rejectionFailure)
            Thread(
                vpnLifecycleReportingCleanup(this, reportedFailure, cleanup),
                "aurora-vpn-teardown-last-resort",
            ).start()
        }
    }
}

private fun vpnLifecycleReportingCleanup(
    lifecycle: VpnProcessLifecycle,
    submissionFailure: Throwable,
    cleanup: Runnable,
): Runnable = Runnable {
    lifecycle.reportVpnLifecycleFailure(submissionFailure)
    cleanup.run()
}
