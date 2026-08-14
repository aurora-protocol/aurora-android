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

    @Test
    fun exposesOnlyTheNativeSessionOperationsRequiredForVpnPacketIo() {
        assertEquals(10, CoreOperation.CLOSE_NATIVE_SESSION.wireValue)
        assertEquals(15, CoreOperation.NEXT_LOCAL_PACKET.wireValue)
        assertEquals(16, CoreOperation.BEGIN_NATIVE_SESSION_JSON.wireValue)
        assertEquals(17, CoreOperation.COMPLETE_NATIVE_SESSION_RAW.wireValue)
        assertEquals(18, CoreOperation.INGRESS_LOCAL_PACKET_JSON.wireValue)
    }
}
