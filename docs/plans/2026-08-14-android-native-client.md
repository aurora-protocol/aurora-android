# Android Native Client Implementation Plan

## Purpose

Deliver a private Android client that uses Android's VPN API for platform
integration while retaining protocol, cryptographic, admission, and transport
decisions in Aurora Core. The first release supports `arm64-v8a` and `x86_64`
on Android API 26 and later.

## Preconditions

- Pin a reviewed Aurora Core revision in the Android build.
- Build Core locally as a C shared library with the Android NDK for both
  supported ABIs.
- Do not include operational signed-seed roots in source control. Release
  packaging injects and validates the sealed root asset before signing.
- Parse only Core's bounded private mobile-FFI reservation envelope on Android;
  never parse its opaque provisioning payload or Aurora network messages.

## Work Items

### 1. Core Reservation Binary ABI

Use Core C ABI operation 19 for the existing native provisioning reservation
request. Its bounded private result contains a three-byte provisioning length,
opaque provisioning bytes, fixed-size spent-hint key and relay-bucket
identifier, and a big-endian expiry. Reuse Core's reservation logic and make
both sides fail closed for malformed input, unavailable trust, reused hints,
invalid output fields, truncation, or trailing bytes. Clear the result envelope
and all partial field copies on failure; transfer only reservation-owned byte
arrays on success.

Write tests before implementation for successful binary output, one-shot
reservation behavior, malformed input, allocation-failure scrubbing, and C ABI
dispatch. Verify with focused tests, `go vet`, race tests, and both Android
shared-library builds.

### 2. Reproducible Android Build

Create a Gradle application using a stable JDK 17, Kotlin, Android Gradle
Plugin, and API baseline. Add a script that requires a pinned sibling Core
checkout, builds both Core shared libraries, checks their ELF architecture and
required ABI symbols, and places generated output outside version control.

The build must assemble debug and unsigned release variants without a device.
CI must independently rebuild Core rather than consuming local generated
files.

### 3. Narrow JNI Boundary

Create one C dispatcher and one Kotlin bridge for Core ABI calls. Enforce
positive result lengths, strict operation-specific input bounds, and a fixed
maximum output allocation. Copy Core output once, invoke zero-and-free, and
clear temporary buffers on every success and failure path. JNI must not cache
packet or provisioning arrays.

Add unit tests for malformed JNI result framing and ensure native symbol checks
cover all shipped ABIs.

### 4. Trust and Encrypted State

Load the sealed signed-seed trust asset with an explicit size limit and pass it
to Core before importing, reserving, validating, or starting a native session.
Use an Android Keystore AES-GCM key for provisioning and reservation metadata.
Treat keystore invalidation, malformed ciphertext, absent roots, or Core
rejection as non-recoverable session-start failures until the user reimports a
valid provisioning item.

Cover loader bounds, Core ordering, encrypted-store round trips, corruption,
and explicit byte clearing with JVM tests.

### 5. Issuer and VPN Session

Implement a foreground `VpnService` that reserves provisioning through Core,
performs one strict bounded HTTPS issuer exchange, passes the opaque response
back to Core, and creates a full-route dual-stack TUN interface. Exclude the
application UID from the VPN before Core opens carrier sockets. Drive bounded
TUN input and Core output workers with deterministic stop, revoke, network
loss, and fatal-error cleanup.

Test issuer shape validation, redirect rejection, response limits, worker
shutdown, and lifecycle state transitions. Connected-device validation covers
consent, IPv4/IPv6 TCP and UDP, DNS, network changes, and teardown.

### 6. Minimal Native UI and Delivery Gates

Provide a small native activity for provisioning import, VPN consent, connect,
disconnect, and error recovery. It must never display secrets or raw network
data. Add release gates requiring the canonical trust asset, validated Core
ABI, unsigned release assembly, lint, JVM tests, and device coverage before a
signed production package can be approved.

## Acceptance Criteria

- No Kotlin or C code parses or generates protocol bytes.
- Missing or invalid sealed roots prevent all tunnel starts.
- Core reservations are one-shot and Android persists only opaque metadata.
- Both ABIs build as ELF shared libraries with the required Core exports.
- VPN traffic is fail-closed and carrier sockets cannot loop through its TUN.
- Local and CI verification pass before each change is merged.
