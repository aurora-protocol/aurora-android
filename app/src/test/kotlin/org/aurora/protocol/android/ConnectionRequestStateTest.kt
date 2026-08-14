package org.aurora.protocol.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRequestStateTest {
    @Test
    fun importBlocksConnectionUntilPersistenceCompletes() {
        val state = ConnectionRequestState()

        assertTrue(state.beginImport())
        assertFalse(state.beginConnectionRequest())

        state.completeImport()

        assertTrue(state.beginConnectionRequest())
    }

    @Test
    fun pendingConnectionBlocksConcurrentImportAndDuplicateConnect() {
        val state = ConnectionRequestState()

        assertTrue(state.beginConnectionRequest())
        assertFalse(state.beginImport())
        assertFalse(state.beginConnectionRequest())
    }

    @Test
    fun cancellationAndConsumptionInvalidatePendingConnection() {
        val state = ConnectionRequestState()

        assertTrue(state.beginConnectionRequest())
        state.cancelConnectionRequest()
        assertFalse(state.consumeConnectionRequest())

        assertTrue(state.beginConnectionRequest())
        assertTrue(state.consumeConnectionRequest())
        assertFalse(state.consumeConnectionRequest())
    }
}
