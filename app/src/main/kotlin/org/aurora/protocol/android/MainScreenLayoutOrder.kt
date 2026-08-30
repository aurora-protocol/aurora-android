package org.aurora.protocol.android

internal enum class MainScreenContentBlock {
    STATUS,
    VPN_COMMANDS,
    IMPORT,
}

internal fun mainScreenContentBlocks(): List<MainScreenContentBlock> = listOf(
    MainScreenContentBlock.STATUS,
    MainScreenContentBlock.VPN_COMMANDS,
    MainScreenContentBlock.IMPORT,
)
