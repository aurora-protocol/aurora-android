#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE_DIR="${AURORA_CORE_DIR:-$ROOT/../aurora-core}"
SOURCE_PATH="${AURORA_SIGNED_SEED_TRUST_PATH:-}"
RESOURCE_PATH="$ROOT/app/src/main/assets/AuroraSignedSeedTrust.bin"

if [ -z "$SOURCE_PATH" ]; then
    printf 'AURORA_SIGNED_SEED_TRUST_PATH is required\n' >&2
    exit 1
fi
if [ ! -f "$SOURCE_PATH" ]; then
    printf 'signed-seed trust file is unavailable: %s\n' "$SOURCE_PATH" >&2
    exit 1
fi
if [ ! -d "$CORE_DIR/cmd/auroractl" ]; then
    printf 'aurora-core checkout is unavailable: %s\n' "$CORE_DIR" >&2
    exit 1
fi

(
    cd "$CORE_DIR"
    GOTOOLCHAIN="${GOTOOLCHAIN:-go1.26.6}" GOCACHE="${GOCACHE:-$ROOT/build/go-cache}" \
        go run ./cmd/auroractl check-native-provisioning-trust "$SOURCE_PATH"
)

mkdir -p "$(dirname "$RESOURCE_PATH")"
umask 077
install -m 600 "$SOURCE_PATH" "$RESOURCE_PATH"
printf 'prepared sealed native trust resource\n'
