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
release packaging injects it only after its SHA-256 digest is matched against
an independently reviewed release value and the pinned Core revision confirms
its canonical encoding.

### Provisioning and State

Provisioning input is size-bounded before it is read. The selected entry and a
bounded reservation ledger are committed together in one AES-GCM encrypted,
`AtomicFile`-backed state under an Android Keystore key. Consumption first
rewrites that state without the active entry and only then returns the entry,
so a failed or interrupted write cannot expose an unledgered reservation.

The ledger stores only the SHA-256 digest of the imported source plus at most
64 spent-hint keys and their expiries; it never stores a second plaintext copy
of the source. Keys supplied by the caller's FFI envelope have no encoded
expiry, so they are conservatively retained with a `Long.MAX_VALUE` sentinel.
The caller, persisted, and newly returned keys are deduplicated and must fit the
same 64-key bound before the combined state commits. Entries with real expiries
are pruned before reservation. Importing a different source resets the prior
source's ledger, matching the Apple active source replacement policy;
consequently switching from source A to B and later back to A is an explicit
history reset, not a promise to remember every source ever imported.
Reimporting the same wallet retains its complete hint union and advances to a
later usable entry even if a later envelope omits hints supplied earlier.

Normal consumption or active-reservation clearing preserves the ledger and its
Keystore key. Authenticated-state corruption remains fail-closed and is not
silently deleted; recovery requires a deliberate internal purge (or an Android
application-data reset), which removes both the blob and key and therefore also
resets local reservation history. Legacy reservation-only blobs migrate by
conservatively carrying their active spent-hint key forward until the next
validated source binds it. Logs and UI state expose only status
classifications, never provisioning bytes, root data, issuer responses, or
packet contents.

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
   foreground state. Service callbacks atomically detach the active generation
   and remove the foreground state without waiting for network or native close
   calls. A process-scoped single-thread teardown owner closes those detached
   resources, remains live across service-instance destruction and recreation,
   and rejects reconnects until both resource cleanup and any canceled
   establishment work finish. Destruction completes the establishment gate
   only for commands the executor returns as never started; a command that has
   begun retains the process gate through its final late-session cleanup.
   Runtime close is completion-bearing: if a packet worker already owns
   shutdown, every concurrent close joins that shutdown before teardown
   ownership can be released.

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

The per-packet ingress path uses Core's binary operation 14 directly. Its
private mobile-FFI result is a canonical QUIC varint packet count followed by
three-byte big-endian lengths and opaque packet bodies. The JNI bridge admits
at most the one-byte Core status plus Core's one-megabyte result bound. Kotlin
accepts at most 64 non-empty packets of at most 65,535 bytes, rejects
non-minimal counts, truncation, and trailing data, and clears the owned result
envelope on every completion path. Successfully decoded packet copies transfer
to the tunnel runtime, which clears them after writing; partially decoded
copies are cleared before a malformed result is rejected. Packet traffic is
never expanded into JSON, base64, or immutable strings on this hot path.

Provisioning reservations use Core's binary operation 19. The private result is
an exact three-byte big-endian provisioning length, 1..1 MiB opaque
provisioning, 48-byte spent-hint key, 16-byte relay-bucket identifier, and
positive signed 64-bit expiry. Kotlin rejects truncation, trailing bytes, and
out-of-range fields; it clears the result envelope on every path, clears all
partially copied fields on failure, and transfers only the three buffers owned
by the reservation on success. No provisioning credential is expanded into JSON,
base64, or immutable strings. Android still does not parse the provisioning
source or any Aurora network message. It parses only Core's private request
envelope (source length plus spent-hint list) so it can hash the opaque source
and append durable spent-hint keys before invoking Core. That parser mirrors
Core's separate 16 MiB wallet-source and 64-key bounds. Core alone validates the
source and selects the next wallet entry.

## HTTP Boundary

Issuer work is accepted only when it contains a non-empty handle, an HTTPS
origin with no credentials, an absolute carrier path, and a non-empty body.
The HTTP client disables caching and cookies, rejects redirects, requires a
200 response, applies connect/read timeouts plus one 45-second monotonic budget
for the complete exchange, and enforces a one-megabyte response limit before
the response is passed to Core. A per-exchange watchdog closes a connection
that stalls during request upload, response headers, or a slow-drip body; every
completion path cancels and shuts down that watchdog before releasing exchange
ownership. If the platform close call has already unblocked the network request
but is itself still returning, the timed-out caller is released while the
exchange stays poisoned against reuse; the watchdog retains ownership only
until close completes, then shuts down without being interrupted. A failed
asynchronous close permanently poisons that exchange object instead of
allowing a second request to overlap a connection whose teardown is uncertain.

## Verification

- JVM tests cover byte clearing, trust-loader validation, encrypted store
  round-trips, issuer request validation, redirect rejection, response bounds,
  tunnel worker shutdown, non-blocking lifecycle teardown and its concurrency
  ownership, canonical binary packet-list and provisioning-reservation framing,
  result-buffer ownership, and the JNI bridge's operation and size contract.
- Native build checks produce ELF libraries for both supported ABIs and verify
  the embedded clean-Core revision and Go/toolchain build settings, exact
  reviewed Core/JNI interface, dependency linkage, and 16 KiB LOAD-segment
  alignment. The APK is also checked for 16 KiB zip alignment, the exact
  permission/component surface, and its compiled backup and network policy so
  the native client remains compatible with 4 KiB and 16 KiB Android devices
  without silently widening its platform access.
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
