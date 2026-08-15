#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
ANDROID_SDK_HOME="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_SDK_HOME}/ndk/27.1.12297006}"

if [ ! -f "$APK" ]; then
    printf 'release APK is unavailable: %s\n' "$APK" >&2
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
if [ ! -x "$toolchain/llvm-readelf" ]; then
    printf 'Android NDK host toolchain is unavailable: %s\n' "$toolchain" >&2
    exit 1
fi

temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

for abi in arm64-v8a x86_64; do
    library="$temporary_directory/$abi-libaurora_android_jni.so"
    unzip -p "$APK" "lib/$abi/libaurora_android_jni.so" > "$library"
    test -s "$library"
    "$toolchain/llvm-readelf" -d "$library" | rg -F 'Shared library: [libauroracore.so]' >/dev/null
done
