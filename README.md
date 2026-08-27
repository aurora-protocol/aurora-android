# Aurora Android

Aurora Android is the platform VPN client for Aurora. Android code owns the
application and VPN lifecycles, encrypted local storage, strict issuer HTTPS
exchange, and the JNI boundary. Protocol, cryptographic, carrier, and packet
transformation decisions remain in the pinned `aurora-core` implementation.

The client targets Android API 36, supports API 26 and later, and builds
`arm64-v8a` and `x86_64` native libraries.

## Prerequisites

- JDK 17.
- Android SDK platform 36 and build-tools 36.0.0.
- Android NDK 27.1.12297006 and CMake 3.22.1.
- Go 1.26.6.
- A clean `aurora-core` checkout at the revision recorded in
  [`aurora-core.revision`](aurora-core.revision). By default it is expected at
  `../aurora-core`; set `AURORA_CORE_DIR` to use a different location.

Set `JAVA_HOME` to the JDK 17 installation and `ANDROID_SDK_ROOT` (or
`ANDROID_HOME`) to the Android SDK. The native build uses that SDK's pinned NDK
unless the Aurora-specific
`AURORA_ANDROID_NDK_HOME` override is set explicitly. Ambient
`ANDROID_NDK_HOME` values are intentionally ignored so CI cannot silently use a
different preinstalled NDK.

## Local verification

Create the non-production trust fixture used by local and CI builds:

```sh
./scripts/write-test-native-trust-resource.sh
```

Record that fixture's explicit non-production digest, then run the same primary
gates as CI:

```sh
AURORA_RELEASE_TRUST_SHA256=a35ee395e5aa78773b61ab8b2ef4f1a92564a6ccbabc44298b65551cf5106677
export AURORA_RELEASE_TRUST_SHA256
./gradlew --no-daemon \
  :app:testDebugUnitTest \
  :app:testReleaseUnitTest \
  :app:lintDebug \
  :app:lintRelease \
  :app:assembleDebug \
  :app:assembleRelease
./scripts/verify-release-native-linkage.sh
```

The Gradle native tasks build the pinned Core checkout for both supported ABIs.
They disable ambient Go workspaces and persistent Go settings, keep module
resolution read-only, and fix the architecture and cgo toolchain inputs so a
developer machine cannot silently widen or retarget the native artifact.
Generated native libraries, the trust asset, Gradle output, and CMake output are
ignored by Git. The release package gate verifies the exact ABI set, native
architecture, embedded clean-Core revision and Go toolchain provenance,
reviewed exported interface, linkage and SONAMEs,
RELRO/eager-binding/non-executable-stack hardening, and 16 KiB ELF and APK
alignment. It also checks the release identity/SDK/debug flags, exact permission
and component surface, VPN intent and foreground-service policy, packaged
backup/network restrictions, and byte-for-byte identity of the validated trust
asset.

## Production trust material

Do not commit operational trust roots. Prepare a release asset only from a
separately supplied, canonical trust file whose SHA-256 digest was reviewed out
of band:

```sh
AURORA_SIGNED_SEED_TRUST_PATH=/secure/path/AuroraSignedSeedTrust.bin \
  AURORA_RELEASE_TRUST_SHA256=<reviewed-lowercase-sha256> \
  ./scripts/prepare-native-trust-resource.sh
```

The preparation and release verification scripts ask Core to validate the
resource at the pinned ABI revision and require the resource to match the
reviewed digest. A missing, substituted, malformed, or rejected asset fails the
release build closed. Set `AURORA_RELEASE_TRUST_SHA256` to that same reviewed
digest for every release Gradle and package-verification invocation.

## Design

See [`docs/design/android-native-client.md`](docs/design/android-native-client.md)
for the security boundaries and lifecycle design, and
[`docs/plans/2026-08-14-android-native-client.md`](docs/plans/2026-08-14-android-native-client.md)
for the implementation and acceptance criteria.
