package org.aurora.protocol.android

internal fun VpnProcessLifecycle.beginConnectionWork(leaseId: Long, connectionGeneration: Long): Boolean =
    synchronized(lock) {
        activeConnection?.takeIf {
            it.leaseId == leaseId && it.generation == connectionGeneration
        }?.let { connection ->
            if (connection.connectionWorkStarted || connection.connectionWorkComplete) {
                return false
            }
            connection.connectionWorkStarted = true
            return true
        }
        activeTeardown?.takeIf {
            it.leaseId == leaseId && it.connectionGeneration == connectionGeneration
        }?.let { teardown ->
            if (teardown.connectionWorkStarted || teardown.connectionWorkComplete) {
                return false
            }
            teardown.connectionWorkStarted = true
            return true
        }
        false
    }

internal fun VpnProcessLifecycle.discardConnectionWork(leaseId: Long, connectionGeneration: Long) =
    synchronized(lock) {
        activeConnection?.takeIf {
            it.leaseId == leaseId && it.generation == connectionGeneration
        }?.let { connection ->
            if (!connection.connectionWorkStarted) {
                connection.connectionWorkComplete = true
            }
            return@synchronized
        }
        activeTeardown?.takeIf {
            it.leaseId == leaseId && it.connectionGeneration == connectionGeneration
        }?.let { teardown ->
            if (!teardown.connectionWorkStarted) {
                teardown.connectionWorkComplete = true
                finishTeardownIfComplete(teardown)
            }
        }
    }

internal fun VpnProcessLifecycle.finishConnectionWork(leaseId: Long, connectionGeneration: Long) =
    synchronized(lock) {
        val connection = activeConnection
        if (connection?.leaseId == leaseId && connection.generation == connectionGeneration) {
            if (connection.connectionWorkStarted) {
                connection.connectionWorkComplete = true
            }
            return@synchronized
        }
        activeTeardown?.takeIf {
            it.leaseId == leaseId && it.connectionGeneration == connectionGeneration
        }?.let { teardown ->
            if (teardown.connectionWorkStarted) {
                teardown.connectionWorkComplete = true
                finishTeardownIfComplete(teardown)
            }
        }
    }
