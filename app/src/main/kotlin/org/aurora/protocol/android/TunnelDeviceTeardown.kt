package org.aurora.protocol.android

/**
 * Closes a tunnel device's descriptor and packet streams as one unit, so the
 * orchestration stays testable without android.os.ParcelFileDescriptor. Every
 * component is attempted; the first failure is thrown with the rest suppressed.
 */
internal class TunnelDeviceTeardown(
    private val descriptor: AutoCloseable,
    private val input: AutoCloseable,
    private val output: AutoCloseable,
) : AutoCloseable {
    override fun close() {
        collectCleanupFailures(descriptor::close, input::close, output::close)?.let { throw it }
    }
}
