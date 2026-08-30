package org.aurora.protocol.android

import java.util.UUID

internal const val connectVpnAction = "org.aurora.protocol.android.action.CONNECT"
internal const val disconnectVpnAction = "org.aurora.protocol.android.action.DISCONNECT"
internal const val connectVpnRequestIdExtra = "org.aurora.protocol.android.extra.CONNECT_REQUEST_ID"
internal const val vpnServiceRequestTimeoutMillis = 10_000L
internal val vpnServiceProcessSessionId: String = UUID.randomUUID().toString()
