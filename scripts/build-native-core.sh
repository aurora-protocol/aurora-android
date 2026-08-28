#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CORE_DIR="${AURORA_CORE_DIR:-$ROOT/../aurora-core}"
# Single source of truth for the pinned aurora-core revision; CI reads the same
# file so the checkout ref and this guard cannot drift apart.
EXPECTED_CORE_REVISION="$(cat "$ROOT/aurora-core.revision")"
ANDROID_SDK_HOME="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
ANDROID_NDK_HOME="${AURORA_ANDROID_NDK_HOME:-${ANDROID_SDK_HOME}/ndk/27.1.12297006}"
OUTPUT_ROOT="$ROOT/app/build/generated/auroracore"
GO_TOOLCHAIN="${GOTOOLCHAIN:-go1.26.6}"
GO_CACHE="${GOCACHE:-$ROOT/build/go-cache}"

if [ ! -f "$CORE_DIR/go.mod" ]; then
    printf 'aurora-core checkout is unavailable: %s\n' "$CORE_DIR" >&2
    exit 1
fi
if [ ! -d "$CORE_DIR/.git" ]; then
    printf 'aurora-core checkout must be a full checkout, not a linked worktree (Go buildvcs cannot stamp VCS provenance there): %s\n' "$CORE_DIR" >&2
    exit 1
fi
if [ ! -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" ]; then
    printf 'Android NDK is unavailable: %s\n' "$ANDROID_NDK_HOME" >&2
    exit 1
fi
if ! grep -Fx 'Pkg.Revision = 27.1.12297006' "$ANDROID_NDK_HOME/source.properties" >/dev/null 2>&1; then
    printf 'Android NDK does not match the pinned revision: %s\n' "$ANDROID_NDK_HOME" >&2
    exit 1
fi
if [ "$GO_TOOLCHAIN" != 'go1.26.6' ]; then
    printf 'GOTOOLCHAIN must match the pinned Android Core toolchain: go1.26.6\n' >&2
    exit 1
fi
actual_core_revision="$(git -C "$CORE_DIR" rev-parse HEAD)"
if [ "$actual_core_revision" != "$EXPECTED_CORE_REVISION" ]; then
    printf 'aurora-core revision does not match the Android ABI pin\n' >&2
    printf '  expected: %s (from aurora-core.revision)\n' "$EXPECTED_CORE_REVISION" >&2
    printf '  actual:   %s (in %s)\n' "$actual_core_revision" "$CORE_DIR" >&2
    printf 'Point AURORA_CORE_DIR at a separate clean checkout of the pinned revision.\n' >&2
    exit 1
fi
if [ -n "$(git -C "$CORE_DIR" status --porcelain=v1 --untracked-files=all)" ]; then
    printf 'aurora-core checkout must be clean for a reproducible Android native build: %s\n' "$CORE_DIR" >&2
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
    expected_machine="$4"
    output_dir="$OUTPUT_ROOT/$abi"
    output="$output_dir/libauroracore.so"
    header="$output_dir/libauroracore.h"
    elf_header="$output_dir/elf-header.txt"
    build_info="$output_dir/go-build-info.txt"

    mkdir -p "$output_dir" "$GO_CACHE"
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
            GOAMD64=v1 \
            GOARM64=v8.0 \
            CGO_ENABLED=1 \
            CGO_CFLAGS='-O2 -g' \
            CGO_CPPFLAGS= \
            CGO_CXXFLAGS='-O2 -g' \
            CGO_FFLAGS='-O2 -g' \
            CGO_LDFLAGS='-O2 -g' \
            GOOS=android \
            GOARCH="$goarch" \
            CC="$toolchain/$compiler" \
            CXX="$toolchain/$compiler++" \
            AR="$toolchain/llvm-ar" \
            go build -buildvcs=true -trimpath -buildmode=c-shared \
                -ldflags=-extldflags=-Wl,-soname,libauroracore.so,-z,max-page-size=16384,-z,common-page-size=16384 \
                -o "$output" ./mobile/auroracore
    )
    test -s "$output"
    test -s "$header"
    "$toolchain/llvm-nm" -D --defined-only "$output" > "$output_dir/dynamic-symbols.txt"
    for required_symbol in AuroraCoreCall AuroraCoreFree AuroraCoreZeroFree; do
        grep -F "$required_symbol" "$header" >/dev/null
        grep -E " $required_symbol$" "$output_dir/dynamic-symbols.txt" >/dev/null
    done
    printf '%s\n' AuroraCoreCall AuroraCoreFree AuroraCoreZeroFree \
        | LC_ALL=C sort > "$output_dir/expected-core-symbols.txt"
    awk '$NF ~ /^AuroraCore/ { print $NF }' "$output_dir/dynamic-symbols.txt" \
        | LC_ALL=C sort > "$output_dir/actual-core-symbols.txt"
    if ! cmp -s "$output_dir/expected-core-symbols.txt" "$output_dir/actual-core-symbols.txt"; then
        printf 'native Core public ABI differs from the reviewed Android interface: %s\n' "$output" >&2
        diff -u "$output_dir/expected-core-symbols.txt" "$output_dir/actual-core-symbols.txt" >&2 || true
        exit 1
    fi
    "$toolchain/llvm-readelf" -hW "$output" > "$elf_header"
    grep -F 'Class:                             ELF64' "$elf_header" >/dev/null
    grep -F 'Data:                              2' "$elf_header" | grep -F 'little endian' >/dev/null
    grep -F "Machine:                           $expected_machine" "$elf_header" >/dev/null
    grep -F 'Type:                              DYN (Shared object file)' "$elf_header" >/dev/null
    "$toolchain/llvm-readelf" -d "$output" | grep -F 'Library soname: [libauroracore.so]' >/dev/null
    GOTOOLCHAIN="$GO_TOOLCHAIN" \
        GOENV=off \
        GOWORK=off \
        GODEBUG= \
        GOEXPERIMENT= \
        GOFIPS140=off \
        go version -m "$output" > "$build_info"
    grep -E ': go1[.]26[.]6$' "$build_info" >/dev/null
    awk '$1 == "path" && $2 == "github.com/aurora-protocol/aurora-core/mobile/auroracore" { found = 1 } END { exit found ? 0 : 1 }' \
        "$build_info"
    for required_build_setting in \
        '-buildmode=c-shared' \
        '-compiler=gc' \
        '-trimpath=true' \
        'CGO_ENABLED=1' \
        "GOARCH=$goarch" \
        'GOOS=android' \
        'vcs=git' \
        "vcs.revision=$EXPECTED_CORE_REVISION" \
        'vcs.modified=false'; do
        awk -v expected="$required_build_setting" \
            '$1 == "build" && $2 == expected { found = 1 } END { exit found ? 0 : 1 }' \
            "$build_info"
    done
    case "$goarch" in
        arm64) required_architecture_setting='GOARM64=v8.0' ;;
        amd64) required_architecture_setting='GOAMD64=v1' ;;
    esac
    awk -v expected="$required_architecture_setting" \
        '$1 == "build" && $2 == expected { found = 1 } END { exit found ? 0 : 1 }' \
        "$build_info"
    alignments="$output_dir/load-alignments.txt"
    "$toolchain/llvm-readelf" -lW "$output" | awk '$1 == "LOAD" { print $NF }' > "$alignments"
    test -s "$alignments"
    if grep -Fvx '0x4000' "$alignments" >/dev/null; then
        printf 'native Core LOAD segment is not 16 KiB aligned: %s\n' "$output" >&2
        exit 1
    fi
    rm -f \
        "$alignments" \
        "$build_info" \
        "$elf_header" \
        "$output_dir/actual-core-symbols.txt" \
        "$output_dir/dynamic-symbols.txt" \
        "$output_dir/expected-core-symbols.txt"
}

build_abi arm64-v8a arm64 aarch64-linux-android26-clang AArch64
build_abi x86_64 amd64 x86_64-linux-android26-clang 'Advanced Micro Devices X86-64'

if [ -n "$(git -C "$CORE_DIR" status --porcelain=v1 --untracked-files=all)" ]; then
    printf 'native build modified the pinned aurora-core checkout: %s\n' "$CORE_DIR" >&2
    exit 1
fi
