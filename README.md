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
  `../aurora-core`; set `AURORA_CORE_DIR` to use a different location. The
  checkout must be a full clone; linked worktrees are rejected because Go
  cannot stamp VCS provenance from them.

The pin in `aurora-core.revision` (and the matching CI checkout ref) advances
deliberately: bump it after reviewing an `aurora-core` change that affects the
mobile package's behavior, then rebuild the native libraries and re-run
`./scripts/aurora-android-check.sh` before committing. Core changes that never
enter `mobile/auroracore`'s dependency graph do not require a bump.

Set `JAVA_HOME` to the JDK 17 installation and `ANDROID_SDK_ROOT` (or
`ANDROID_HOME`) to the Android SDK. The native build uses that SDK's pinned NDK
unless the Aurora-specific
`AURORA_ANDROID_NDK_HOME` override is set explicitly. Ambient
`ANDROID_NDK_HOME` values are intentionally ignored so CI cannot silently use a
different preinstalled NDK.

## Local verification

Run every local gate CI runs, in order, with a single command:

```sh
./scripts/aurora-android-check.sh
```

The script creates the non-production trust fixture used by local and CI
builds, runs the same Gradle verification tasks as CI (unit tests, lint, and
debug/release assembly), and verifies the release package contents. It honors
the same environment overrides as CI (`AURORA_CORE_DIR`,
`AURORA_RELEASE_TRUST_SHA256`, `GOTOOLCHAIN`) and defaults to the pinned
values, including the fixture's explicit non-production digest. The individual
scripts under [`scripts/`](scripts/) remain available for running a single
gate.

Three environment settings must line up for the run to succeed: `JAVA_HOME`
naming a JDK 17 installation (the script falls back to the Homebrew
`openjdk@17` location when unset), `ANDROID_SDK_ROOT`/`ANDROID_HOME` naming
the SDK that contains the pinned NDK 27.1.12297006, and `AURORA_CORE_DIR`
naming a Core checkout at the revision recorded in `aurora-core.revision`.
When the default `../aurora-core` checkout has moved ahead of the pin, point
`AURORA_CORE_DIR` at a checkout that matches it, or the native build and
release verification fail on the Core revision mismatch:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
  AURORA_CORE_DIR=../aurora-core-android-pin \
  ./scripts/aurora-android-check.sh
```

The framework-bound surface that JVM tests cannot cover — the JNI bridge into
libauroracore, the keystore-backed reservation store, and the tunnel device's
real file descriptors — has instrumented tests under
[`app/src/androidTest/`](app/src/androidTest/). With a device or emulator
connected (`adb devices`), run them against the debug build:

```sh
./gradlew :app:connectedDebugAndroidTest
```

`LabDriveTest` is skipped during the normal connected suite because it imports
and consumes live, one-time provisioning on a stateful lab device. Run it only
on a dedicated device with the intended lab provisioning asset:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.aurora.protocol.android.LabDriveTest \
  -Pandroid.testInstrumentationRunnerArguments.auroraLabDrive=true
```

The run uses the same environment as a local build, including
`AURORA_CORE_DIR` for the pinned Core checkout. The pipe-descriptor tunnel
tests require API 33 or later and are skipped on older devices.

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

Author the sealed file from reviewed signed-seed roots and deployment trust
tuples with the portable core:

```sh
(cd ../aurora-core && go run ./cmd/auroractl build-native-provisioning-trust \
  --spec /secure/path/trust-spec.json --out /secure/path/AuroraSignedSeedTrust.bin)
```

The command writes a canonical, mode-0600 blob and refuses to overwrite an
existing file unless `--force` is given; the preparation script re-validates it
with `auroractl check-native-provisioning-trust` at the pinned Core revision.

## Design

See [`docs/design/android-native-client.md`](docs/design/android-native-client.md)
for the security boundaries and lifecycle design, and
[`docs/plans/2026-08-14-android-native-client.md`](docs/plans/2026-08-14-android-native-client.md)
for the implementation and acceptance criteria.
