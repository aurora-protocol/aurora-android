#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE_DIR="${AURORA_CORE_DIR:-$ROOT/../aurora-core}"
RESOURCE_PATH="$ROOT/app/src/main/assets/AuroraSignedSeedTrust.bin"

if [ -L "$RESOURCE_PATH" ] || [ ! -f "$RESOURCE_PATH" ] || [ ! -s "$RESOURCE_PATH" ]; then
    printf 'sealed native trust resource is unavailable: %s\n' "$RESOURCE_PATH" >&2
    exit 1
fi
if [ ! -d "$CORE_DIR/cmd/auroractl" ]; then
    printf 'aurora-core checkout is unavailable: %s\n' "$CORE_DIR" >&2
    exit 1
fi

(
    cd "$CORE_DIR"
    GOTOOLCHAIN="${GOTOOLCHAIN:-go1.26.6}" GOCACHE="${GOCACHE:-$ROOT/build/go-cache}" \
        go run ./cmd/auroractl check-native-provisioning-trust "$RESOURCE_PATH"
)

printf 'release_native_trust_check passed=true\n'
