package org.aurora.protocol.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningExpiryMonitorTest {
    @Test
    fun `monitor expires at the exact epoch boundary`() {
        var nowMillis = 122_250L
        val scheduler = RecordingScheduler()
        val expired = mutableListOf<Long>()
        val monitor = monitor({ nowMillis }, scheduler, expired::add)

        monitor.update(123)

        assertEquals(750L, scheduler.delayMillis)
        nowMillis = 123_000
        scheduler.runPending()
        assertEquals(listOf(123L), expired)
        assertFalse(scheduler.hasPending)
    }

    @Test
    fun `monitor rechecks a backward wall clock shift before expiring`() {
        var nowMillis = 122_500L
        val scheduler = RecordingScheduler()
        val expired = mutableListOf<Long>()
        val monitor = monitor({ nowMillis }, scheduler, expired::add)
        monitor.update(123)

        nowMillis = 122_000
        scheduler.runPending()

        assertTrue(expired.isEmpty())
        assertEquals(1_000L, scheduler.delayMillis)
        nowMillis = 123_000
        scheduler.runPending()
        assertEquals(listOf(123L), expired)
    }

    @Test
    fun `distant expiry uses bounded wall clock rechecks`() {
        val scheduler = RecordingScheduler()
        val monitor = monitor({ 100_000L }, scheduler) {}

        monitor.update(10_000)

        assertEquals(wallClockRecheckMillis, scheduler.delayMillis)
    }

    @Test
    fun `stopping monitor cancels pending expiry work`() {
        val scheduler = RecordingScheduler()
        val monitor = monitor({ 122_000L }, scheduler) {}
        monitor.update(123)

        monitor.stop()

        assertFalse(scheduler.hasPending)
        assertNull(scheduler.delayMillis)
    }

    @Test
    fun `replacement expiry cancels the older scheduled callback`() {
        var nowMillis = 122_000L
        val scheduler = RecordingScheduler()
        val expired = mutableListOf<Long>()
        val monitor = monitor({ nowMillis }, scheduler, expired::add)
        monitor.update(123)

        monitor.update(125)
        nowMillis = 123_000
        scheduler.runPending()

        assertTrue(expired.isEmpty())
        assertEquals(2_000L, scheduler.delayMillis)
    }

    @Test
    fun `expiry delay handles maximum timestamp without overflow`() {
        assertEquals(Long.MAX_VALUE, millisecondsUntilExpiry(Long.MAX_VALUE, 122_000))
        assertEquals(0L, millisecondsUntilExpiry(123, 123_000))
    }

    private fun monitor(
        currentTimeMillis: () -> Long,
        scheduler: RecordingScheduler,
        onExpired: (Long) -> Unit,
    ): ProvisioningExpiryMonitor = ProvisioningExpiryMonitor(
        currentTimeMillis = currentTimeMillis,
        schedule = scheduler::schedule,
        cancel = scheduler::cancel,
        onExpired = onExpired,
    )

    private class RecordingScheduler {
        private var pending: Runnable? = null
        var delayMillis: Long? = null
            private set
        val hasPending: Boolean
            get() = pending != null

        fun schedule(action: Runnable, delayMillis: Long) {
            check(pending == null)
            pending = action
            this.delayMillis = delayMillis
        }

        fun cancel(action: Runnable) {
            if (pending === action) {
                pending = null
                delayMillis = null
            }
        }

        fun runPending() {
            val action = requireNotNull(pending)
            pending = null
            delayMillis = null
            action.run()
        }
    }
}
