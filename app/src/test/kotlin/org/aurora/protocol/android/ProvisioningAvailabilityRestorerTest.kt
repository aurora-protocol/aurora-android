package org.aurora.protocol.android

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.aurora.protocol.android.core.StoredReservationAvailability

class ProvisioningAvailabilityRestorerTest {
    @Test
    fun `stored reservation remains checking until delegated work completes`() {
        val channel = VpnTunnelStatus()
        val executor = DeferredExecutor()
        var checks = 0
        var checkedAt = 0L
        val restorer = restorer(channel, executor) { nowUnix ->
            ++checks
            checkedAt = nowUnix
            StoredReservationAvailability.AVAILABLE
        }

        restorer.start()

        assertEquals(TunnelStatus.CHECKING_PROVISIONING, channel.status)
        assertEquals(0, checks)
        assertTrue(executor.hasPending)
        executor.runPending()
        assertEquals(TunnelStatus.IDLE, channel.status)
        assertEquals(1, checks)
        assertEquals(nowUnix, checkedAt)
    }

    @Test
    fun `empty storage restores provisioning required status`() {
        val channel = VpnTunnelStatus()
        val restorer = restorer(channel, Executor(Runnable::run)) { StoredReservationAvailability.MISSING }

        restorer.start()

        assertEquals(TunnelStatus.PROVISIONING_REQUIRED, channel.status)
    }

    @Test
    fun `expired storage restores a removable expired status`() {
        val channel = VpnTunnelStatus()
        val restorer = restorer(channel, Executor(Runnable::run)) { StoredReservationAvailability.EXPIRED }

        restorer.start()

        assertEquals(TunnelStatus.PROVISIONING_EXPIRED, channel.status)
    }

    @Test
    fun `newer lifecycle transition rejects an older availability result`() {
        val channel = VpnTunnelStatus()
        val executor = DeferredExecutor()
        val restorer = restorer(channel, executor) { StoredReservationAvailability.AVAILABLE }
        restorer.start()

        channel.publish(TunnelStatus.CONNECTING)
        executor.runPending()

        assertEquals(TunnelStatus.CONNECTING, channel.status)
    }

    @Test
    fun `storage mutation invalidates an availability result before it can publish`() {
        val channel = VpnTunnelStatus()
        val executor = DeferredExecutor()
        val restorer = restorer(channel, executor) { StoredReservationAvailability.AVAILABLE }
        restorer.start()

        restorer.invalidate()
        executor.runPending()

        assertEquals(TunnelStatus.CHECKING_PROVISIONING, channel.status)
    }

    @Test
    fun `storage failure fails closed and is reported`() {
        val channel = VpnTunnelStatus()
        val failure = IllegalStateException("keystore unavailable")
        val observed = mutableListOf<Throwable>()
        val restorer = ProvisioningAvailabilityRestorer(
            tunnelStatus = channel,
            storedReservationAvailability = { throw failure },
            currentUnixTime = { nowUnix },
            executor = Executor(Runnable::run),
            onFailure = observed::add,
        )

        restorer.start()

        assertEquals(TunnelStatus.PROVISIONING_REQUIRED, channel.status)
        assertEquals(listOf(failure), observed)
    }

    @Test
    fun `dispatch failure fails closed and is reported`() {
        val channel = VpnTunnelStatus()
        val failure = RejectedExecutionException("worker unavailable")
        val observed = mutableListOf<Throwable>()
        val restorer = ProvisioningAvailabilityRestorer(
            tunnelStatus = channel,
            storedReservationAvailability = { throw AssertionError("must not run") },
            currentUnixTime = { nowUnix },
            executor = Executor { throw failure },
            onFailure = observed::add,
        )

        restorer.start()

        assertEquals(TunnelStatus.PROVISIONING_REQUIRED, channel.status)
        assertEquals(listOf(failure), observed)
    }

    private fun restorer(
        channel: VpnTunnelStatus,
        executor: Executor,
        check: (Long) -> StoredReservationAvailability,
    ): ProvisioningAvailabilityRestorer = ProvisioningAvailabilityRestorer(
        tunnelStatus = channel,
        storedReservationAvailability = check,
        currentUnixTime = { nowUnix },
        executor = executor,
        onFailure = { throw AssertionError("unexpected failure", it) },
    )

    private companion object {
        const val nowUnix = 122L
    }

    private class DeferredExecutor : Executor {
        private var pending: Runnable? = null
        val hasPending: Boolean
            get() = pending != null

        override fun execute(command: Runnable) {
            check(pending == null)
            pending = command
        }

        fun runPending() {
            requireNotNull(pending).also { pending = null }.run()
        }
    }
}
