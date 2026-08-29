package org.aurora.protocol.android

import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.aurora.protocol.android.core.NativePacketSession

class VpnServiceLifecycleTest {
    @Test
    fun `a stale generation stop is ignored and a repeated start shares the connection`() {
        val executor = Executors.newSingleThreadExecutor()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val process = VpnProcessLifecycle(failures::add, executor)
        val lifecycle = process.acquire()
        val generation = lifecycle.acceptedGeneration(31)

        try {
            assertEquals(VpnConnectionStop.Ignored, lifecycle.stop(expectedGeneration = generation + 1))
            assertFalse(lifecycle.teardownInProgress)

            assertEquals(VpnConnectionStart.Shared, lifecycle.start(32))
            assertFalse(lifecycle.teardownInProgress)

            val stop = lifecycle.stop(expectedGeneration = generation) as VpnConnectionStop.Started
            assertEquals(32, stop.serviceStartId)
            lifecycle.finishLifecycleStop(stop.teardownId)
            lifecycle.discardConnectionWork(generation)
            assertTrue(awaitCondition { !lifecycle.teardownInProgress })
            assertTrue(failures.isEmpty())
        } finally {
            lifecycle.release()
            process.shutdownForTest()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `start, promotion, and stop publish tunnel status classifications`() {
        val executor = Executors.newSingleThreadExecutor()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val channel = VpnTunnelStatus()
        val observed = mutableListOf<TunnelStatus>()
        channel.observe(observed::add)
        val process = VpnProcessLifecycle(failures::add, executor, tunnelStatus = channel)
        val lifecycle = process.acquire()
        val session = CountingResource()
        val runtime = CountingResource()
        val generation = lifecycle.acceptedGeneration(41)

        try {
            assertEquals(TunnelStatus.CONNECTING, channel.status)

            assertTrue(lifecycle.beginConnectionWork(generation))
            assertTrue(lifecycle.attachSession(generation, session))
            assertTrue(lifecycle.promoteRuntime(generation, session, runtime))
            assertEquals(TunnelStatus.CONNECTED, channel.status)

            val stop = lifecycle.stop(expectedGeneration = generation) as VpnConnectionStop.Started
            assertEquals(TunnelStatus.DISCONNECTING, channel.status)
            lifecycle.finishLifecycleStop(stop.teardownId)
            lifecycle.finishConnectionWork(generation)
            assertTrue(awaitCondition { channel.status == TunnelStatus.IDLE })

            assertEquals(
                listOf(
                    TunnelStatus.CONNECTING,
                    TunnelStatus.CONNECTED,
                    TunnelStatus.DISCONNECTING,
                    TunnelStatus.IDLE,
                ),
                observed,
            )
            assertTrue(failures.isEmpty())
        } finally {
            lifecycle.release()
            process.shutdownForTest()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `failure stops publish failed while rejected and shared starts publish nothing`() {
        val executor = Executors.newSingleThreadExecutor()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val channel = VpnTunnelStatus()
        val observed = mutableListOf<TunnelStatus>()
        channel.observe(observed::add)
        val process = VpnProcessLifecycle(failures::add, executor, tunnelStatus = channel)
        val lifecycle = process.acquire()
        val other = process.acquire()
        val generation = lifecycle.acceptedGeneration(52)

        try {
            assertEquals(VpnConnectionStart.Rejected, other.start(51))
            assertEquals(VpnConnectionStart.Shared, lifecycle.start(53))
            assertEquals(listOf(TunnelStatus.CONNECTING), observed)

            assertTrue(lifecycle.beginConnectionWork(generation))
            val stop = lifecycle.stop(
                expectedGeneration = generation,
                failed = true,
            ) as VpnConnectionStop.Started
            assertEquals(TunnelStatus.DISCONNECTING, channel.status)
            lifecycle.finishLifecycleStop(stop.teardownId)
            lifecycle.finishConnectionWork(generation)
            assertTrue(awaitCondition { channel.status == TunnelStatus.FAILED })

            assertEquals(
                listOf(TunnelStatus.CONNECTING, TunnelStatus.DISCONNECTING, TunnelStatus.FAILED),
                observed,
            )
            assertTrue(failures.isEmpty())
        } finally {
            other.release()
            lifecycle.release()
            process.shutdownForTest()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `normal stop requires a new provisioning entry after one-shot consumption`() {
        val executor = Executors.newSingleThreadExecutor()
        val channel = VpnTunnelStatus()
        val process = VpnProcessLifecycle({}, executor, tunnelStatus = channel)
        val lifecycle = process.acquire()
        val generation = lifecycle.acceptedGeneration(54)

        try {
            assertTrue(lifecycle.beginConnectionWork(generation))
            assertTrue(lifecycle.markProvisioningRequired(generation))
            val stop = lifecycle.stop(expectedGeneration = generation) as VpnConnectionStop.Started
            lifecycle.finishLifecycleStop(stop.teardownId)
            lifecycle.finishConnectionWork(generation)

            assertTrue(awaitCondition { channel.status == TunnelStatus.PROVISIONING_REQUIRED })
        } finally {
            lifecycle.release()
            process.shutdownForTest()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `late consumption mark upgrades an already detached failure outcome`() {
        val executor = Executors.newSingleThreadExecutor()
        val channel = VpnTunnelStatus()
        val process = VpnProcessLifecycle({}, executor, tunnelStatus = channel)
        val lifecycle = process.acquire()
        val generation = lifecycle.acceptedGeneration(55)

        try {
            assertTrue(lifecycle.beginConnectionWork(generation))
            val stop = lifecycle.stop(
                expectedGeneration = generation,
                failed = true,
            ) as VpnConnectionStop.Started

            assertFalse(lifecycle.markProvisioningRequired(generation))
            lifecycle.finishLifecycleStop(stop.teardownId)
            lifecycle.finishConnectionWork(generation)

            assertTrue(awaitCondition { channel.status == TunnelStatus.FAILED_REQUIRES_PROVISIONING })
        } finally {
            lifecycle.release()
            process.shutdownForTest()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `platform stop and callback return do not wait for a blocking resource close`() {
        val executor = Executors.newSingleThreadExecutor()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val process = VpnProcessLifecycle(failures::add, executor)
        val lifecycle = process.acquire()
        val resource = BlockingResource()
        val generation = lifecycle.acceptedGeneration(11)
        assertTrue(lifecycle.beginConnectionWork(generation))
        assertTrue(lifecycle.attachSession(generation, resource))
        lifecycle.finishConnectionWork(generation)
        val platformStopped = CountDownLatch(1)
        val callbackReturned = CountDownLatch(1)

        val callback = thread(start = true, name = "vpn-stop-callback-test") {
            val result = lifecycle.stop(expectedGeneration = generation)
            platformStopped.countDown()
            lifecycle.finishLifecycleStop((result as VpnConnectionStop.Started).teardownId)
            callbackReturned.countDown()
        }

        try {
            assertTrue(resource.closeStarted.await(2, TimeUnit.SECONDS))
            assertTrue(platformStopped.await(2, TimeUnit.SECONDS))
            assertTrue(callbackReturned.await(2, TimeUnit.SECONDS))
            callback.join(2_000)
            assertFalse(callback.isAlive)
            assertEquals(1, resource.closeCalls.get())
            assertTrue(lifecycle.teardownInProgress)
            assertTrue(failures.isEmpty())
        } finally {
            resource.allowClose.countDown()
            callback.join(2_000)
            lifecycle.release()
            process.shutdownForTest()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `teardown executor is not starved by blocked establishment work`() {
        val commandExecutor = Executors.newSingleThreadExecutor()
        val teardownExecutor = Executors.newSingleThreadExecutor()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val process = VpnProcessLifecycle(failures::add, teardownExecutor)
        val lifecycle = process.acquire()
        val commandStarted = CountDownLatch(1)
        val releaseCommand = CountDownLatch(1)
        commandExecutor.execute {
            commandStarted.countDown()
            check(releaseCommand.await(10, TimeUnit.SECONDS))
        }
        assertTrue(commandStarted.await(2, TimeUnit.SECONDS))
        val resource = BlockingResource()
        val generation = lifecycle.acceptedGeneration(12)
        assertTrue(lifecycle.beginConnectionWork(generation))
        assertTrue(lifecycle.attachSession(generation, resource))
        lifecycle.finishConnectionWork(generation)

        val stop = lifecycle.stop(expectedGeneration = generation) as VpnConnectionStop.Started
        lifecycle.finishLifecycleStop(stop.teardownId)

        try {
            assertTrue(resource.closeStarted.await(2, TimeUnit.SECONDS))
            assertEquals(1, resource.closeCalls.get())
            assertTrue(failures.isEmpty())
        } finally {
            resource.allowClose.countDown()
            releaseCommand.countDown()
            commandExecutor.shutdown()
            lifecycle.release()
            process.shutdownForTest()
            assertTrue(commandExecutor.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(teardownExecutor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `reconnect remains rejected until close and canceled connection work both finish`() {
        val executor = Executors.newSingleThreadExecutor()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val process = VpnProcessLifecycle(failures::add, executor)
        val lifecycle = process.acquire()
        val resource = BlockingResource()
        val generation = lifecycle.acceptedGeneration(13)
        assertTrue(lifecycle.beginConnectionWork(generation))
        assertTrue(lifecycle.attachSession(generation, resource))
        val stop = lifecycle.stop(expectedGeneration = generation) as VpnConnectionStop.Started
        lifecycle.finishLifecycleStop(stop.teardownId)

        try {
            assertTrue(resource.closeStarted.await(2, TimeUnit.SECONDS))
            assertEquals(VpnConnectionStart.Rejected, lifecycle.start(14))

            resource.allowClose.countDown()
            assertTrue(resource.closeFinished.await(2, TimeUnit.SECONDS))
            assertTrue(lifecycle.teardownInProgress)
            assertEquals(VpnConnectionStart.Rejected, lifecycle.start(15))

            lifecycle.finishConnectionWork(generation)
            assertTrue(awaitCondition { !lifecycle.teardownInProgress })
            closeFreshConnection(lifecycle, 16)
            assertTrue(failures.isEmpty())
        } finally {
            resource.allowClose.countDown()
            lifecycle.release()
            process.shutdownForTest()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `concurrent fatal and explicit stops close the detached resource exactly once`() {
        val executor = Executors.newSingleThreadExecutor()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val process = VpnProcessLifecycle(failures::add, executor)
        val lifecycle = process.acquire()
        val resource = BlockingResource()
        val generation = lifecycle.acceptedGeneration(17)
        assertTrue(lifecycle.beginConnectionWork(generation))
        assertTrue(lifecycle.attachSession(generation, resource))
        lifecycle.finishConnectionWork(generation)
        val releaseStops = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<VpnConnectionStop>()
        val callbacks = listOf(
            thread(start = true, name = "vpn-fatal-stop-test") {
                releaseStops.await()
                results += lifecycle.stop(expectedGeneration = generation)
            },
            thread(start = true, name = "vpn-explicit-stop-test") {
                releaseStops.await()
                results += lifecycle.stop(serviceStartId = 18)
            },
            thread(start = true, name = "vpn-revoke-stop-test") {
                releaseStops.await()
                results += lifecycle.stop()
            },
        )

        try {
            releaseStops.countDown()
            callbacks.forEach { callback -> callback.join(2_000) }
            assertTrue(callbacks.none(Thread::isAlive))
            val started = results.filterIsInstance<VpnConnectionStop.Started>()
            assertEquals(1, started.size)
            lifecycle.finishLifecycleStop(started.single().teardownId)
            assertTrue(resource.closeStarted.await(2, TimeUnit.SECONDS))
            assertEquals(1, resource.closeCalls.get())
            assertTrue(failures.isEmpty())
        } finally {
            resource.allowClose.countDown()
            lifecycle.release()
            process.shutdownForTest()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `generic close failure is reported and does not strand teardown state`() {
        val executor = Executors.newSingleThreadExecutor()
        val loggedClasses = ConcurrentLinkedQueue<String>()
        val process = VpnProcessLifecycle(
            onTeardownFailure = { error -> loggedClasses += error.javaClass.simpleName },
            initialTeardownExecutor = executor,
        )
        val lifecycle = process.acquire()
        val generation = lifecycle.acceptedGeneration(19)
        assertTrue(lifecycle.beginConnectionWork(generation))
        assertTrue(lifecycle.attachSession(generation, FailingResource()))
        lifecycle.finishConnectionWork(generation)
        val stop = lifecycle.stop(expectedGeneration = generation) as VpnConnectionStop.Started
        lifecycle.finishLifecycleStop(stop.teardownId)

        try {
            assertTrue(awaitCondition { loggedClasses.isNotEmpty() })
            assertEquals(listOf("FailingResourceException"), loggedClasses.toList())
            assertTrue(awaitCondition { !lifecycle.teardownInProgress })
            closeFreshConnection(lifecycle, 20)
        } finally {
            lifecycle.release()
            process.shutdownForTest()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `session close joiner waits for the sole close owner`() {
        val delegate = BlockingNativePacketSession()
        val session = CloseOnceNativePacketSession(delegate)
        val ownerReturned = CountDownLatch(1)
        val joinerEntered = CountDownLatch(1)
        val joinerReturned = CountDownLatch(1)
        val owner = thread(start = true, name = "session-close-owner-test") {
            try {
                session.close()
            } finally {
                ownerReturned.countDown()
            }
        }
        assertTrue(delegate.closeStarted.await(2, TimeUnit.SECONDS))
        val joiner = thread(start = true, name = "session-close-joiner-test") {
            joinerEntered.countDown()
            try {
                session.close()
            } finally {
                joinerReturned.countDown()
            }
        }

        try {
            assertTrue(joinerEntered.await(2, TimeUnit.SECONDS))
            assertFalse(joinerReturned.await(200, TimeUnit.MILLISECONDS))
            assertEquals(1, delegate.closeCalls.get())

            delegate.allowClose.countDown()
            assertTrue(ownerReturned.await(2, TimeUnit.SECONDS))
            assertTrue(joinerReturned.await(2, TimeUnit.SECONDS))
            owner.join(2_000)
            joiner.join(2_000)
            assertFalse(owner.isAlive)
            assertFalse(joiner.isAlive)
            assertEquals(1, delegate.closeCalls.get())
        } finally {
            delegate.allowClose.countDown()
            owner.join(2_000)
            joiner.join(2_000)
        }
    }

    @Test
    fun `destroy discards only a queued command while process cleanup remains queued`() {
        val commandExecutor = Executors.newSingleThreadExecutor()
        val commandBlockerStarted = CountDownLatch(1)
        val releaseCommandBlocker = CountDownLatch(1)
        commandExecutor.execute {
            commandBlockerStarted.countDown()
            try {
                releaseCommandBlocker.await(10, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                // shutdownNow() owns cancellation of this unrelated running command.
            }
        }
        assertTrue(commandBlockerStarted.await(2, TimeUnit.SECONDS))
        val teardownExecutor = Executors.newSingleThreadExecutor()
        val teardownBlockerStarted = CountDownLatch(1)
        val releaseTeardownBlocker = CountDownLatch(1)
        teardownExecutor.execute {
            teardownBlockerStarted.countDown()
            check(releaseTeardownBlocker.await(10, TimeUnit.SECONDS))
        }
        assertTrue(teardownBlockerStarted.await(2, TimeUnit.SECONDS))
        val failures = ConcurrentLinkedQueue<Throwable>()
        val process = VpnProcessLifecycle(failures::add, teardownExecutor)
        val oldService = process.acquire()
        val generation = oldService.acceptedGeneration(21)
        val queuedWorkRan = AtomicBoolean()
        val command = VpnConnectionCommand(oldService, generation) {
            queuedWorkRan.set(true)
        }
        commandExecutor.execute(command)
        val stop = oldService.stop(expectedGeneration = generation) as VpnConnectionStop.Started
        oldService.finishLifecycleStop(stop.teardownId)
        val discarded = commandExecutor.shutdownNow()
        assertEquals(listOf(command), discarded)
        discarded.filterIsInstance<VpnConnectionCommand>().forEach { it.discardIfQueued() }
        oldService.release()
        val newService = process.acquire()

        try {
            assertEquals(VpnConnectionStart.Rejected, oldService.start(22))
            assertEquals(VpnConnectionStart.Rejected, newService.start(23))
            assertTrue(newService.teardownInProgress)
            assertFalse(queuedWorkRan.get())

            releaseTeardownBlocker.countDown()
            assertTrue(awaitCondition { !newService.teardownInProgress })
            closeFreshConnection(newService, 24)
            assertTrue(failures.isEmpty())
        } finally {
            releaseCommandBlocker.countDown()
            releaseTeardownBlocker.countDown()
            newService.release()
            process.shutdownForTest()
            assertTrue(commandExecutor.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(teardownExecutor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `destroyed service keeps started work poisoned through late session cleanup`() {
        val commandExecutor = Executors.newSingleThreadExecutor()
        val teardownExecutor = Executors.newSingleThreadExecutor()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val process = VpnProcessLifecycle(failures::add, teardownExecutor)
        val oldService = process.acquire()
        val generation = oldService.acceptedGeneration(25)
        val workStarted = CountDownLatch(1)
        val allowLateSession = CountDownLatch(1)
        val lateSession = AtomicReference<CountingResource?>()
        val lateAttachAccepted = AtomicReference<Boolean?>()
        val command = VpnConnectionCommand(oldService, generation) {
            workStarted.countDown()
            awaitUninterruptibly(allowLateSession)
            val resource = CountingResource()
            lateSession.set(resource)
            val attached = oldService.attachSession(generation, resource)
            lateAttachAccepted.set(attached)
            if (!attached) {
                resource.close()
            }
        }
        commandExecutor.execute(command)
        assertTrue(workStarted.await(2, TimeUnit.SECONDS))

        val stop = oldService.stop(expectedGeneration = generation) as VpnConnectionStop.Started
        oldService.finishLifecycleStop(stop.teardownId)
        val discarded = commandExecutor.shutdownNow()
        assertTrue(discarded.isEmpty())
        discarded.filterIsInstance<VpnConnectionCommand>().forEach { it.discardIfQueued() }
        oldService.release()
        val cleanupPassed = CountDownLatch(1)
        teardownExecutor.execute { cleanupPassed.countDown() }
        val newService = process.acquire()

        try {
            assertTrue(cleanupPassed.await(2, TimeUnit.SECONDS))
            assertTrue(newService.teardownInProgress)
            assertEquals(VpnConnectionStart.Rejected, oldService.start(26))
            assertEquals(VpnConnectionStart.Rejected, newService.start(27))

            allowLateSession.countDown()
            assertTrue(commandExecutor.awaitTermination(2, TimeUnit.SECONDS))
            assertEquals(false, lateAttachAccepted.get())
            assertEquals(1, lateSession.get()?.closeCalls?.get())
            assertTrue(awaitCondition { !newService.teardownInProgress })
            closeFreshConnection(newService, 28)
            assertTrue(failures.isEmpty())
        } finally {
            allowLateSession.countDown()
            newService.release()
            process.shutdownForTest()
            assertTrue(commandExecutor.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(teardownExecutor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `executor rejection transfers sole cleanup ownership without blocking or stranding state`() {
        val rejectedExecutor = Executors.newSingleThreadExecutor().apply { shutdown() }
        val recoveryExecutor = Executors.newSingleThreadExecutor()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val process = VpnProcessLifecycle(
            onTeardownFailure = failures::add,
            initialTeardownExecutor = rejectedExecutor,
            teardownExecutorFactory = { recoveryExecutor },
        )
        val lifecycle = process.acquire()
        val resource = BlockingResource()
        val generation = lifecycle.acceptedGeneration(29)
        assertTrue(lifecycle.beginConnectionWork(generation))
        assertTrue(lifecycle.attachSession(generation, resource))
        lifecycle.finishConnectionWork(generation)
        val callbackReturned = CountDownLatch(1)

        val callback = thread(start = true, name = "vpn-rejected-teardown-test") {
            val stop = lifecycle.stop(expectedGeneration = generation) as VpnConnectionStop.Started
            lifecycle.finishLifecycleStop(stop.teardownId)
            callbackReturned.countDown()
        }

        try {
            assertTrue(resource.closeStarted.await(2, TimeUnit.SECONDS))
            assertTrue(callbackReturned.await(2, TimeUnit.SECONDS))
            callback.join(2_000)
            assertFalse(callback.isAlive)
            assertEquals(1, resource.closeCalls.get())
            assertEquals(1, failures.count { it is RejectedExecutionException })

            resource.allowClose.countDown()
            assertTrue(resource.closeFinished.await(2, TimeUnit.SECONDS))
            assertTrue(awaitCondition { !lifecycle.teardownInProgress })
            closeFreshConnection(lifecycle, 30)
        } finally {
            resource.allowClose.countDown()
            callback.join(2_000)
            lifecycle.release()
            process.shutdownForTest()
            assertTrue(recoveryExecutor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private fun VpnServiceLifecycle.acceptedGeneration(serviceStartId: Int): Long {
        return (start(serviceStartId) as VpnConnectionStart.Accepted).generation
    }

    private fun closeFreshConnection(lifecycle: VpnServiceLifecycle, serviceStartId: Int) {
        val generation = lifecycle.acceptedGeneration(serviceStartId)
        assertTrue(lifecycle.beginConnectionWork(generation))
        lifecycle.finishConnectionWork(generation)
        val stop = lifecycle.stop(expectedGeneration = generation) as VpnConnectionStop.Started
        lifecycle.finishLifecycleStop(stop.teardownId)
        assertTrue(awaitCondition { !lifecycle.teardownInProgress })
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return true
            }
            Thread.yield()
        }
        return condition()
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }

    private class CountingResource : AutoCloseable {
        val closeCalls = AtomicInteger()

        override fun close() {
            closeCalls.incrementAndGet()
        }
    }

    private class BlockingResource : AutoCloseable {
        val closeCalls = AtomicInteger()
        val closeStarted = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)

        override fun close() {
            closeCalls.incrementAndGet()
            closeStarted.countDown()
            try {
                check(allowClose.await(10, TimeUnit.SECONDS))
            } finally {
                closeFinished.countDown()
            }
        }
    }

    private class FailingResource : AutoCloseable {
        override fun close() {
            throw FailingResourceException("issuer=https://sensitive.example")
        }
    }

    private class FailingResourceException(message: String) : IOException(message)

    private class BlockingNativePacketSession : NativePacketSession {
        private val deferredPackets = LinkedBlockingQueue<ByteArray>()
        val closeCalls = AtomicInteger()
        val closeStarted = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val interrupted = AtomicBoolean()

        override fun ingressLocalPacket(packet: ByteArray): List<ByteArray> = emptyList()

        override fun nextLocalPacket(): ByteArray = deferredPackets.take()

        override fun close() {
            closeCalls.incrementAndGet()
            closeStarted.countDown()
            try {
                check(allowClose.await(10, TimeUnit.SECONDS))
            } catch (error: InterruptedException) {
                interrupted.set(true)
                throw error
            } finally {
                deferredPackets.offer(byteArrayOf(0x45))
                closeFinished.countDown()
            }
        }
    }
}
