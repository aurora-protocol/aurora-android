package org.aurora.protocol.android

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.aurora.protocol.android.core.NativePacketSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CloseOnceNativePacketSessionTest {
    @Test
    fun `a second close is a no-op after a clean first close`() {
        val delegate = RecordingNativePacketSession()
        val session = CloseOnceNativePacketSession(delegate)

        session.close()
        session.close()

        assertEquals(1, delegate.closeCalls.get())
    }

    @Test
    fun `the first close failure is recorded and surfaced by every later close`() {
        val failure = IOException("native session close failed")
        val delegate = RecordingNativePacketSession(failure)
        val session = CloseOnceNativePacketSession(delegate)

        val first = assertThrows(IOException::class.java) { session.close() }
        val second = assertThrows(IOException::class.java) { session.close() }

        assertSame(failure, first)
        assertSame(failure, second)
        assertEquals(1, delegate.closeCalls.get())
    }

    private class RecordingNativePacketSession(
        private val closeFailure: Throwable? = null,
    ) : NativePacketSession {
        val closeCalls = AtomicInteger()

        override fun ingressLocalPacket(packet: ByteArray): List<ByteArray> = emptyList()

        override fun nextLocalPacket(): ByteArray = ByteArray(0)

        override fun close() {
            closeCalls.incrementAndGet()
            closeFailure?.let { throw it }
        }
    }
}
