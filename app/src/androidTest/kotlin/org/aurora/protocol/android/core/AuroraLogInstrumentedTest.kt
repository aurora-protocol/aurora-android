package org.aurora.protocol.android.core

import org.junit.Test

/** Smoke coverage for the android.util.Log boundary on device. */
class AuroraLogInstrumentedTest {
    @Test
    fun debugLoggingNeverThrowsWhetherOrNotTheTagIsEnabled() {
        AuroraLog.debug("instrumented smoke", IllegalStateException("probe"))
        AuroraLog.debug("instrumented smoke", RuntimeException())
    }
}
