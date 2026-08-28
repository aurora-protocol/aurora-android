package org.aurora.protocol.android

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TunnelDeviceTeardownTest {
    @Test
    fun `descriptor failure stays primary while stream failures are suppressed`() {
        val descriptorFailure = IOException("tun device revoked")
        val inputFailure = IOException("input close failed")
        val outputFailure = IOException("output close failed")

        val failure = assertCloseThrows(
            TunnelDeviceTeardown(
                descriptor = failing(descriptorFailure),
                input = failing(inputFailure),
                output = failing(outputFailure),
            ),
        )

        assertSame(descriptorFailure, failure)
        assertEquals(listOf(inputFailure, outputFailure), failure.suppressed.toList())
    }

    @Test
    fun `every component is attempted even when earlier closes fail`() {
        val closed = mutableListOf<String>()
        val teardown = TunnelDeviceTeardown(
            descriptor = recording("descriptor", closed, IOException("descriptor failed")),
            input = recording("input", closed, IOException("input failed")),
            output = recording("output", closed, null),
        )

        assertCloseThrows(teardown)
        assertEquals(listOf("descriptor", "input", "output"), closed)
    }

    @Test
    fun `a lone stream failure propagates and a clean close stays quiet`() {
        val streamFailure = IOException("output close failed")
        val failure = assertCloseThrows(
            TunnelDeviceTeardown(
                descriptor = quiet(),
                input = quiet(),
                output = failing(streamFailure),
            ),
        )
        assertSame(streamFailure, failure)
        assertEquals(emptyList<Throwable>(), failure.suppressed.toList())

        TunnelDeviceTeardown(quiet(), quiet(), quiet()).close()
    }

    private fun quiet(): AutoCloseable = AutoCloseable {}

    private fun failing(failure: IOException): AutoCloseable = AutoCloseable { throw failure }

    private fun recording(name: String, closed: MutableList<String>, failure: IOException?): AutoCloseable =
        AutoCloseable {
            closed += name
            failure?.let { throw it }
        }

    private fun assertCloseThrows(teardown: AutoCloseable): Throwable = try {
        teardown.close()
        throw AssertionError("close should have thrown")
    } catch (failure: IOException) {
        failure
    }
}
