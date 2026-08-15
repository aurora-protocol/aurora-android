package org.aurora.protocol.android.core

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTrustConfiguratorTest {
    @Test
    fun configuresBoundedTrustAndClearsTheConfigurationBuffer() {
        val trust = byteArrayOf(0x01, 0x02, 0x03)
        var observed: ByteArray? = null
        var released: ByteArray? = null

        NativeTrustConfigurator.configure(
            openResource = { ByteArrayInputStream(trust) },
            configureCore = { encoded ->
                observed = encoded.copyOf()
                released = encoded
                true
            },
        )

        assertArrayEquals(trust, observed)
        assertArrayEquals(ByteArray(trust.size), released)
    }

    @Test
    fun rejectsEmptyOversizedAndRejectedTrustResources() {
        assertThrows(NativeTrustConfigurationException::class.java) {
            NativeTrustConfigurator.configure(
                openResource = { ByteArrayInputStream(ByteArray(0)) },
                configureCore = { true },
            )
        }
        assertThrows(NativeTrustConfigurationException::class.java) {
            NativeTrustConfigurator.configure(
                openResource = { ByteArrayInputStream(ByteArray(NativeTrustConfigurator.maximumResourceBytes + 1)) },
                configureCore = { true },
            )
        }
        val trust = byteArrayOf(0x01)
        var released: ByteArray? = null
        val rejection = assertThrows(NativeTrustConfigurationException::class.java) {
            NativeTrustConfigurator.configure(
                openResource = { ByteArrayInputStream(trust) },
                configureCore = { encoded ->
                    released = encoded
                    false
                },
            )
        }

        assertEquals(NativeTrustConfigurationException.Reason.CORE_REJECTED, rejection.reason)
        assertTrue(released != null)
        assertArrayEquals(ByteArray(trust.size), released)
    }
}
