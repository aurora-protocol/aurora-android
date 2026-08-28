#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE_DIR="${AURORA_CORE_DIR:-$ROOT/../aurora-core}"
EXPECTED_CORE_REVISION="$(cat "$ROOT/aurora-core.revision")"
EXPECTED_TRUST_SHA256="${AURORA_RELEASE_TRUST_SHA256:-}"
RESOURCE_PATH="$ROOT/app/src/main/assets/AuroraSignedSeedTrust.bin"
GO_TOOLCHAIN="${GOTOOLCHAIN:-go1.26.6}"
GO_CACHE="${GOCACHE:-$ROOT/build/go-cache}"

if [ -L "$RESOURCE_PATH" ] || [ ! -f "$RESOURCE_PATH" ] || [ ! -s "$RESOURCE_PATH" ]; then
    printf 'sealed native trust resource is unavailable: %s\n' "$RESOURCE_PATH" >&2
    exit 1
fi
if [ ! -d "$CORE_DIR/cmd/auroractl" ]; then
    printf 'aurora-core checkout is unavailable: %s\n' "$CORE_DIR" >&2
    exit 1
fi
if [ ! -d "$CORE_DIR/.git" ]; then
    printf 'aurora-core checkout must be a full checkout, not a linked worktree (Go buildvcs cannot stamp VCS provenance there): %s\n' "$CORE_DIR" >&2
    exit 1
fi
actual_core_revision="$(git -C "$CORE_DIR" rev-parse HEAD)"
if [ "$actual_core_revision" != "$EXPECTED_CORE_REVISION" ]; then
    printf 'aurora-core revision does not match the Android ABI pin\n' >&2
    printf '  expected: %s (from aurora-core.revision)\n' "$EXPECTED_CORE_REVISION" >&2
    printf '  actual:   %s (in %s)\n' "$actual_core_revision" "$CORE_DIR" >&2
    exit 1
fi
if [ -n "$(git -C "$CORE_DIR" status --porcelain=v1 --untracked-files=all)" ]; then
    printf 'aurora-core checkout must be clean for release trust validation: %s\n' "$CORE_DIR" >&2
    exit 1
fi
if [ "$GO_TOOLCHAIN" != 'go1.26.6' ]; then
    printf 'GOTOOLCHAIN must match the pinned Android Core toolchain: go1.26.6\n' >&2
    exit 1
fi
if [ "${#EXPECTED_TRUST_SHA256}" -ne 64 ]; then
    printf 'AURORA_RELEASE_TRUST_SHA256 must be a reviewed lowercase SHA-256 digest\n' >&2
    exit 1
fi
case "$EXPECTED_TRUST_SHA256" in
    *[!0-9a-f]*)
        printf 'AURORA_RELEASE_TRUST_SHA256 must be a reviewed lowercase SHA-256 digest\n' >&2
        exit 1
        ;;
esac

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{ print $1 }'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{ print $1 }'
    else
        printf 'no SHA-256 utility is available\n' >&2
        return 1
    fi
}

actual_trust_sha256="$(sha256_file "$RESOURCE_PATH")"
if [ "$actual_trust_sha256" != "$EXPECTED_TRUST_SHA256" ]; then
    printf 'sealed native trust resource does not match the reviewed release digest\n' >&2
    printf '  expected: %s\n' "$EXPECTED_TRUST_SHA256" >&2
    printf '  actual:   %s\n' "$actual_trust_sha256" >&2
    exit 1
fi

(
    cd "$CORE_DIR"
    GOTOOLCHAIN="$GO_TOOLCHAIN" \
        GOCACHE="$GO_CACHE" \
        GOENV=off \
        GOWORK=off \
        GOFLAGS=-mod=readonly \
        GODEBUG= \
        GOEXPERIMENT= \
        GOFIPS140=off \
        CGO_ENABLED=0 \
        go run ./cmd/auroractl check-native-provisioning-trust "$RESOURCE_PATH"
)

printf 'release_native_trust_check passed=true sha256=%s\n' "$actual_trust_sha256"
