#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE_DIR="${AURORA_CORE_DIR:-$ROOT/../aurora-core}"
EXPECTED_CORE_REVISION="1bcf6e024e5d91cd3c945c2b6dee8c76611c7a81"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-}/ndk/27.1.12297006}"
OUTPUT_ROOT="$ROOT/app/src/main/jniLibs"
GO_TOOLCHAIN="${GOTOOLCHAIN:-go1.26.6}"
GO_CACHE="${GOCACHE:-$ROOT/build/go-cache}"

if [ ! -f "$CORE_DIR/go.mod" ]; then
    printf 'aurora-core checkout is unavailable: %s\n' "$CORE_DIR" >&2
    exit 1
fi
if [ ! -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" ]; then
    printf 'Android NDK is unavailable: %s\n' "$ANDROID_NDK_HOME" >&2
    exit 1
fi
if [ "$(git -C "$CORE_DIR" rev-parse HEAD)" != "$EXPECTED_CORE_REVISION" ]; then
    printf 'aurora-core revision does not match the Android ABI pin\n' >&2
    exit 1
fi

case "$(uname -s)" in
    Darwin) host_tag=darwin-x86_64 ;;
    Linux) host_tag=linux-x86_64 ;;
    *)
        printf 'unsupported native build host: %s\n' "$(uname -s)" >&2
        exit 1
        ;;
esac

toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/bin"
if [ ! -x "$toolchain/llvm-nm" ]; then
    printf 'Android NDK host toolchain is unavailable: %s\n' "$toolchain" >&2
    exit 1
fi

build_abi() {
    abi="$1"
    goarch="$2"
    compiler="$3"
    output_dir="$OUTPUT_ROOT/$abi"
    output="$output_dir/libauroracore.so"
    header="$output_dir/libauroracore.h"

    mkdir -p "$output_dir" "$GO_CACHE"
    (
        cd "$CORE_DIR"
        GOTOOLCHAIN="$GO_TOOLCHAIN" GOCACHE="$GO_CACHE" CGO_ENABLED=1 GOOS=android GOARCH="$goarch" CC="$toolchain/$compiler" \
            go build -trimpath -buildmode=c-shared -o "$output" ./mobile/auroracore
    )
    test -s "$output"
    test -s "$header"
    rg -F 'AuroraCoreCall' "$header" >/dev/null
    rg -F 'AuroraCoreZeroFree' "$header" >/dev/null
    "$toolchain/llvm-nm" -D --defined-only "$output" | rg ' AuroraCore(Call|Free|ZeroFree)$' >/dev/null
}

build_abi arm64-v8a arm64 aarch64-linux-android26-clang
build_abi x86_64 amd64 x86_64-linux-android26-clang
