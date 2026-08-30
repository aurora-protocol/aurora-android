package org.aurora.protocol.android

internal fun AuroraVpnService.establishTunnel(): FileDescriptorTunnelDevice {
    val descriptor = Builder()
        .setSession(applicationInfo.loadLabel(packageManager).toString())
        .setMtu(AuroraVpnService.tunnelMtu)
        .setBlocking(true)
        .addAddress(AuroraVpnService.ipv4Address, AuroraVpnService.ipv4PrefixLength)
        .addAddress(AuroraVpnService.ipv6Address, AuroraVpnService.ipv6PrefixLength)
        .addDnsServer(AuroraVpnService.ipv4Dns)
        .addDnsServer(AuroraVpnService.ipv6Dns)
        .addRoute("0.0.0.0", 0)
        .addRoute("::", 0)
        .addDisallowedApplication(packageName)
        .establish()
        ?: throw IllegalStateException("VPN permission was revoked")
    return FileDescriptorTunnelDevice(descriptor)
}
