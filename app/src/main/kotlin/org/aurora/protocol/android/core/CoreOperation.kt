package org.aurora.protocol.android.core

internal enum class CoreOperation(val wireValue: Int) {
    CONFIGURE_NATIVE_PROVISIONING_TRUST(21),
    RESERVE_NATIVE_PROVISIONING_JSON(22),
}
