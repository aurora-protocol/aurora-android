# Android Native Client Design

## Scope

Aurora Android is a private, platform-specific client application. It provides
an Android VPN integration and delegates all protocol, cryptographic, carrier,
and packet transformation decisions to the portable Core C ABI. Kotlin and C
are limited to lifecycle, platform network I/O, secure local storage, and
bounded translation across the JNI boundary.

## Compatibility Baseline

- Minimum Android API: 26.
- Compile and target Android API: 36.
- Device ABIs: `arm64-v8a` and `x86_64`.
- Native Core source: a pinned Aurora Core revision built locally with the
  installed Android NDK.
- Android build tooling: JDK 17, Android Gradle Plugin 8.13.2, Gradle 8.13,
  Kotlin 2.3.

The application uses the platform VPN API and standard Android framework
components. It has no general-purpose proxy mode in a release build.

## Security Boundaries

### Core Boundary

The Core ABI is the only layer that reads or emits protocol bytes. Android
code does not encode protocol messages, inspect admission material, or make
transport-selection decisions. JNI has one operation dispatcher, validates all
lengths before allocation, copies each Core result once, calls the Core
zero-and-free function, and clears temporary byte arrays promptly.

### Trust Roots

Native provisioning is fail-closed until independently anchored signed-seed
trust roots are configured in Core. Release packaging supplies the canonical
root set as an immutable asset within the signed application. The application
must configure that asset before it imports, reserves, validates, or begins a
native provisioning session. User-imported provisioning and application
preferences are never accepted as a root-configuration channel.

Missing, malformed, oversized, or non-canonical trust data prevents a tunnel
from starting. The root asset is intentionally not stored in this repository;
release packaging injects it after canonical validation.

### Provisioning and State

Provisioning input is size-bounded before it is read. It is encrypted with an
Android Keystore AES-GCM key before being persisted. Reservation metadata is
persisted separately in the same encrypted store so reconnects never reuse an
access hint. Logs and UI state expose only status classifications, never
provisioning bytes, root data, issuer responses, or packet contents.

## VPN Lifecycle

1. The UI asks the operating system for VPN consent.
2. After consent, the service enters foreground state with an active, minimal
   status notification.
3. The service loads and configures the release-sealed trust roots, then
   reserves one provisioning entry through Core.
4. Core returns opaque issuer work. Android verifies the endpoint shape,
   performs one bounded HTTPS POST without redirects, and returns the opaque
   response to Core.
5. The service establishes a TUN interface with IPv4, IPv6, DNS, and default
   routes. The Aurora application UID is explicitly disallowed from that VPN
   so Core's carrier sockets use the underlying network instead of recursing
   into the TUN interface.
6. A bounded input worker reads TUN packets into Core. A bounded output worker
   waits for Core packets and writes them to TUN. Backpressure, invalid
   packets, and Core failures terminate the session rather than dropping into
   an unprotected route.
7. Stop, revoke, and fatal-error paths close the Core handle, close the TUN
   descriptor, cancel workers, clear in-memory sensitive data, and remove the
   foreground state.

No Android-owned socket is used for carrier traffic. The Core native runtime
owns that traffic and operates under the application UID excluded in step 5.

## Native Build and JNI

`scripts/build-native-core.sh` requires a sibling Aurora Core checkout or an
explicit `AURORA_CORE_DIR`. It builds the same portable package for `arm64-v8a`
and `x86_64` as Android shared libraries, generates the C header, and places
only build artifacts under the application native-library directory.

`app/src/main/cpp/aurora_jni.c` links against the generated library and exposes
one Kotlin-native method. It rejects null, negative, and oversized inputs;
does not cache Java arrays; and zeroizes each temporary C buffer before it is
released. ABI operation identifiers live in one Kotlin internal definition and
are covered by bridge tests.

Provisioning reservation metadata is emitted by a Core JSON operation. Kotlin
may validate JSON framing and field sizes, but it does not parse the underlying
binary reservation envelope. This Core operation is a prerequisite for Android
storage because replay-protection identifiers must be persisted without
duplicating protocol parsing in the platform adapter.

## HTTP Boundary

Issuer work is accepted only when it contains a non-empty handle, an HTTPS
origin with no credentials, an absolute carrier path, and a non-empty body.
The HTTP client disables caching and cookies, rejects redirects, requires a
200 response, applies connect/read timeouts, and enforces a one-megabyte
response limit before the response is passed to Core.

## Verification

- JVM tests cover byte clearing, trust-loader validation, encrypted store
  round-trips, issuer request validation, redirect rejection, response bounds,
  tunnel worker shutdown, and JNI result framing.
- Native build checks produce ELF libraries for both supported ABIs and verify
  their exported Core symbols.
- Android builds assemble debug and release variants, lint the application,
  and run JVM tests without a connected device.
- Connected-device validation exercises VPN consent, foreground service
  startup, TUN creation, application-UID exclusion, TCP egress, UDP egress,
  IPv4, IPv6, DNS, network transitions, and stop/revoke cleanup against a
  controlled Aurora deployment.
- A release is blocked unless the sealed trust asset is present, canonical, and
  accepted by Core before the application bundle is signed.

## Non-Goals

This repository does not duplicate Core protocol logic, ship a general local
proxy, persist raw packet capture, or embed operational root material.
