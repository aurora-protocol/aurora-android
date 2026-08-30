package org.aurora.protocol.android.core

import java.net.URI
import java.net.URL

private const val maximumIssuerComponentBytes = 2 * 1024

internal fun issuerEndpointFor(work: NativeIssuerWork): URL {
    require(work.issuerUrl.toExternalForm().utf8Size() in 1..maximumIssuerComponentBytes) {
        "invalid issuer origin"
    }
    val base = work.issuerUrl.toURI()
    require(base.scheme.equals("https", ignoreCase = true) && base.host != null && base.userInfo == null) {
        "invalid issuer origin"
    }
    require(base.rawAuthority != null && !(base.port == -1 && base.rawAuthority.endsWith(':'))) {
        "invalid issuer origin"
    }
    require(base.port == -1 || base.port in 1..65_535) { "invalid issuer origin" }
    require(base.rawPath.isNullOrEmpty()) { "invalid issuer origin" }
    require(base.rawQuery == null && base.rawFragment == null) { "invalid issuer origin" }
    require(work.issuerCarrierPath.utf8Size() in 2..maximumIssuerComponentBytes) {
        "invalid issuer path"
    }
    require(
        work.issuerCarrierPath.startsWith("/") &&
            !work.issuerCarrierPath.contains("//") &&
            !work.issuerCarrierPath.endsWith('/'),
    ) {
        "invalid issuer path"
    }
    require(!work.issuerCarrierPath.contains('?') && !work.issuerCarrierPath.contains('#') && !work.issuerCarrierPath.contains('\\')) {
        "invalid issuer path"
    }
    val carrierPath = URI(null, null, work.issuerCarrierPath, null)
    require(carrierPath.rawPath == work.issuerCarrierPath && carrierPath.normalize().rawPath == work.issuerCarrierPath) {
        "invalid issuer path"
    }
    val endpoint = URI(base.scheme, null, base.host, base.port, work.issuerCarrierPath, null, null).toURL()
    require(endpoint.protocol.equals("https", ignoreCase = true) && endpoint.host.equals(base.host, ignoreCase = true)) {
        "issuer origin changed"
    }
    return endpoint
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
