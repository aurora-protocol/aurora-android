package org.aurora.protocol.android

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        restorer.onMainScreenResumed(refreshAllowed = true)

        assertEquals(TunnelStatus.CHECKING_PROVISIONING, channel.status)
        assertEquals(0, checks)
        assertTrue(executor.hasPending)
        executor.runPending()
        assertEquals(TunnelStatus.IDLE, channel.status)
        assertEquals(1, checks)
        assertEquals(nowUnix, checkedAt)
    }

    @Test
    fun `subsequent screen resume rechecks wall clock expiry`() {
        val channel = VpnTunnelStatus()
        var now = nowUnix
        var availability = StoredReservationAvailability.AVAILABLE
        val checkedAt = mutableListOf<Long>()
        val restorer = ProvisioningAvailabilityRestorer(
            tunnelStatus = channel,
            storedReservationAvailability = { checkedAt += it; availability },
            currentUnixTime = { now },
            executor = Executor(Runnable::run),
            onFailure = { throw AssertionError("unexpected failure", it) },
        )
        restorer.start()

        restorer.onMainScreenResumed(refreshAllowed = true)
        availability = StoredReservationAvailability.EXPIRED
        now = nowUnix + 1
        restorer.onMainScreenResumed(refreshAllowed = true)

        assertEquals(TunnelStatus.PROVISIONING_EXPIRED, channel.status)
        assertEquals(listOf(nowUnix, nowUnix + 1), checkedAt)
    }

    @Test
    fun `valid resumed check preserves a retryable pre-consumption failure`() {
        val channel = VpnTunnelStatus()
        var checks = 0
        val restorer = restorer(channel, Executor(Runnable::run)) {
            ++checks
            StoredReservationAvailability.AVAILABLE
        }
        restorer.start()
        restorer.onMainScreenResumed(refreshAllowed = true)
        channel.publish(TunnelStatus.FAILED)

        restorer.onMainScreenResumed(refreshAllowed = true)

        assertEquals(TunnelStatus.FAILED, channel.status)
        assertEquals(2, checks)
    }

    @Test
    fun `screen resume does not probe non-retryable lifecycle states`() {
        val channel = VpnTunnelStatus()
        var checks = 0
        val restorer = restorer(channel, Executor(Runnable::run)) {
            ++checks
            StoredReservationAvailability.AVAILABLE
        }
        restorer.start()
        restorer.onMainScreenResumed(refreshAllowed = true)

        listOf(
            TunnelStatus.CHECKING_PROVISIONING,
            TunnelStatus.CONNECTING,
            TunnelStatus.CONNECTED,
            TunnelStatus.DISCONNECTING,
            TunnelStatus.PROVISIONING_REQUIRED,
            TunnelStatus.PROVISIONING_EXPIRED,
            TunnelStatus.FAILED_REQUIRES_PROVISIONING,
        ).forEach { status ->
            channel.publish(status)
            restorer.onMainScreenResumed(refreshAllowed = true)
        }

        assertEquals(1, checks)
        assertEquals(TunnelStatus.FAILED_REQUIRES_PROVISIONING, channel.status)
    }

    @Test
    fun `newer lifecycle transition rejects a resumed availability result`() {
        val channel = VpnTunnelStatus()
        val executor = DeferredExecutor()
        val restorer = restorer(channel, executor) { StoredReservationAvailability.AVAILABLE }
        restorer.start()
        restorer.onMainScreenResumed(refreshAllowed = true)
        executor.runPending()

        restorer.onMainScreenResumed(refreshAllowed = true)
        assertEquals(TunnelStatus.CHECKING_PROVISIONING, channel.status)
        channel.publish(TunnelStatus.CONNECTING)
        executor.runPending()

        assertEquals(TunnelStatus.CONNECTING, channel.status)
    }

    @Test
    fun `storage mutation invalidates a resumed availability result`() {
        val channel = VpnTunnelStatus()
        val executor = DeferredExecutor()
        val restorer = restorer(channel, executor) { StoredReservationAvailability.AVAILABLE }
        restorer.start()
        restorer.onMainScreenResumed(refreshAllowed = true)
        executor.runPending()

        restorer.onMainScreenResumed(refreshAllowed = true)
        restorer.invalidate()
        channel.publish(TunnelStatus.PROVISIONING_REQUIRED)
        executor.runPending()

        assertEquals(TunnelStatus.PROVISIONING_REQUIRED, channel.status)
    }

    @Test
    fun `locally pending work suppresses resumed checks`() {
        val channel = VpnTunnelStatus()
        var checks = 0
        val restorer = restorer(channel, Executor(Runnable::run)) {
            ++checks
            StoredReservationAvailability.AVAILABLE
        }
        restorer.start()

        restorer.onMainScreenResumed(refreshAllowed = false)
        restorer.onMainScreenResumed(refreshAllowed = false)

        assertEquals(TunnelStatus.IDLE, channel.status)
        assertEquals(1, checks)
        restorer.onMainScreenResumed(refreshAllowed = true)
        assertEquals(2, checks)
    }

    @Test
    fun `resume refresh gate admits only screens without locally pending work`() {
        assertTrue(provisioningRefreshAllowed(false, false, false, null))
        assertFalse(provisioningRefreshAllowed(true, false, false, null))
        assertFalse(provisioningRefreshAllowed(false, true, false, null))
        assertFalse(provisioningRefreshAllowed(false, false, true, null))
        assertFalse(provisioningRefreshAllowed(false, false, false, VpnServiceCommand.CONNECT))
        assertFalse(provisioningRefreshAllowed(false, false, false, VpnServiceCommand.DISCONNECT))
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
