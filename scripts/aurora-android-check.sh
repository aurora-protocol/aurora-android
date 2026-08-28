#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Single local gate mirroring .github/workflows/ci.yml: prepare the
# non-production trust fixture, run the CI Gradle verification tasks, then
# verify the release package. verify-release-native-trust.sh is intentionally
# not a separate step: the Gradle release graph already runs it through the
# verifyReleaseNativeTrust task (preReleaseBuild), and
# verify-release-native-linkage.sh invokes it again before checking the APK.
#
# Environment overrides match CI and the individual scripts:
#   AURORA_CORE_DIR              pinned aurora-core checkout (default ../aurora-core)
#   AURORA_RELEASE_TRUST_SHA256  default is the non-production fixture digest from ci.yml
#   GOTOOLCHAIN                  default go1.26.6 (the pinned Core toolchain)
#   GOCACHE                      default build/go-cache (set by the called scripts)
#   ANDROID_SDK_ROOT/ANDROID_HOME must point at the pinned Android SDK.

CORE_DIR="${AURORA_CORE_DIR:-$ROOT/../aurora-core}"
TRUST_SHA256="${AURORA_RELEASE_TRUST_SHA256:-3dc6e04540dda6ce4ef828bc2d27ce9e451d956e06baf2622e07d005bfc9488b}"
GO_TOOLCHAIN="${GOTOOLCHAIN:-go1.26.6}"

export AURORA_CORE_DIR="$CORE_DIR"
export AURORA_RELEASE_TRUST_SHA256="$TRUST_SHA256"
export GOTOOLCHAIN="$GO_TOOLCHAIN"

# JDK 17 resolution: honor an explicit JAVA_HOME, fall back to the Homebrew
# JDK 17 installation, otherwise rely on java from PATH (Gradle toolchains and
# the release package gate report their own error if no JDK 17 is reachable).
if [ -n "${JAVA_HOME:-}" ]; then
    if [ ! -x "$JAVA_HOME/bin/java" ]; then
        printf 'JAVA_HOME does not contain a java executable: %s\n' "$JAVA_HOME" >&2
        exit 1
    fi
elif [ -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java ]; then
    JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    export JAVA_HOME
elif ! command -v java >/dev/null 2>&1; then
    printf 'no JDK 17 found: set JAVA_HOME or install openjdk@17\n' >&2
    exit 1
fi

printf '==> preparing non-production native trust test resource\n'
"$ROOT/scripts/write-test-native-trust-resource.sh"

printf '==> running CI Gradle verification tasks\n'
"$ROOT/gradlew" --no-daemon \
    :app:testDebugUnitTest \
    :app:testReleaseUnitTest \
    :app:lintDebug \
    :app:lintRelease \
    :app:assembleDebug \
    :app:assembleRelease

printf '==> verifying release package contents\n'
"$ROOT/scripts/verify-release-native-linkage.sh"

printf 'aurora_android_check passed=true\n'
