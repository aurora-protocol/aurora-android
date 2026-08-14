package org.aurora.protocol.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreOperationTest {
    @Test
    fun reservesNativeProvisioningThroughTheJsonOnlyOperation() {
        assertEquals(22, CoreOperation.RESERVE_NATIVE_PROVISIONING_JSON.wireValue)
    }

    @Test
    fun configuresNativeProvisioningTrustThroughTheDedicatedOperation() {
        assertEquals(21, CoreOperation.CONFIGURE_NATIVE_PROVISIONING_TRUST.wireValue)
    }
}
