#!/usr/bin/env sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
TRUST_RESOURCE="$ROOT/app/src/main/assets/AuroraSignedSeedTrust.bin"
ANDROID_SDK_HOME="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
ANDROID_NDK_HOME="${AURORA_ANDROID_NDK_HOME:-${ANDROID_SDK_HOME}/ndk/27.1.12297006}"
EXPECTED_CORE_REVISION="$(cat "$ROOT/aurora-core.revision")"
EXPECTED_TRUST_SHA256="${AURORA_RELEASE_TRUST_SHA256:-}"
GO_TOOLCHAIN="${GOTOOLCHAIN:-go1.26.6}"
ZIPALIGN="$ANDROID_SDK_HOME/build-tools/36.0.0/zipalign"
AAPT2="$ANDROID_SDK_HOME/build-tools/36.0.0/aapt2"
APKSIGNER="$ANDROID_SDK_HOME/build-tools/36.0.0/apksigner"
APKANALYZER="$ANDROID_SDK_HOME/cmdline-tools/latest/bin/apkanalyzer"

if [ -z "$ANDROID_SDK_HOME" ]; then
    printf 'ANDROID_SDK_ROOT or ANDROID_HOME must point at the pinned Android SDK\n' >&2
    exit 1
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    printf 'JAVA_HOME must point at the pinned JDK 17 installation\n' >&2
    exit 1
fi
java_specification_version="$(
    "$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1 \
        | sed -n 's/^[[:space:]]*java\.specification\.version = //p' \
        | head -n 1
)"
if [ "$java_specification_version" != 17 ]; then
    printf 'release package verification requires JDK 17, got %s\n' \
        "${java_specification_version:-unknown}" >&2
    exit 1
fi
if [ ! -f "$APK" ]; then
    printf 'release APK is unavailable: %s\n' "$APK" >&2
    exit 1
fi
if [ ! -f "$TRUST_RESOURCE" ] || [ ! -s "$TRUST_RESOURCE" ]; then
    printf 'sealed native trust resource is unavailable: %s\n' "$TRUST_RESOURCE" >&2
    exit 1
fi
if [ ! -x "$ZIPALIGN" ] || [ ! -x "$AAPT2" ] || [ ! -x "$APKSIGNER" ]; then
    printf 'pinned Android build tools are unavailable: %s\n' "$(dirname "$ZIPALIGN")" >&2
    exit 1
fi
if ! grep -Fx 'Pkg.Revision=36.0.0' "$(dirname "$ZIPALIGN")/source.properties" >/dev/null 2>&1; then
    printf 'Android build tools do not match the pinned revision: %s\n' "$(dirname "$ZIPALIGN")" >&2
    exit 1
fi
if [ ! -x "$APKANALYZER" ]; then
    printf 'Android APK analyzer is unavailable: %s\n' "$APKANALYZER" >&2
    exit 1
fi

"$ROOT/scripts/verify-release-native-trust.sh"

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

temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

assert_manifest_value() {
    label="$1"
    expected="$2"
    actual="$3"
    if [ "$actual" != "$expected" ]; then
        printf 'release APK %s mismatch: expected %s, got %s\n' "$label" "$expected" "$actual" >&2
        exit 1
    fi
}

assert_manifest_value application-id org.aurora.protocol.android \
    "$("$APKANALYZER" manifest application-id "$APK")"
assert_manifest_value min-sdk 26 "$("$APKANALYZER" manifest min-sdk "$APK")"
assert_manifest_value target-sdk 36 "$("$APKANALYZER" manifest target-sdk "$APK")"
assert_manifest_value debuggable false "$("$APKANALYZER" manifest debuggable "$APK")"
if "$APKSIGNER" verify "$APK" >/dev/null 2>&1; then
    printf 'release package verification must run before signing: %s\n' "$APK" >&2
    exit 1
fi

packaged_manifest="$temporary_directory/AndroidManifest.xml"
"$APKANALYZER" manifest print "$APK" > "$packaged_manifest"
for required_manifest_attribute in \
    'android:name="org.aurora.protocol.android.AuroraApplication"' \
    'android:allowBackup="false"' \
    'android:extractNativeLibs="false"' \
    'android:usesCleartextTraffic="false"'; do
    if ! grep -F "$required_manifest_attribute" "$packaged_manifest" >/dev/null; then
        printf 'release APK manifest is missing required policy: %s\n' "$required_manifest_attribute" >&2
        exit 1
    fi
done

packaged_resources="$temporary_directory/resources.txt"
"$AAPT2" dump resources "$APK" > "$packaged_resources"
assert_manifest_resource() {
    attribute="$1"
    resource_name="$2"
    attribute_lines="$temporary_directory/$attribute-lines.txt"
    grep -F "android:$attribute=\"@ref/" "$packaged_manifest" > "$attribute_lines" || true
    if [ "$(wc -l < "$attribute_lines" | tr -d '[:space:]')" -ne 1 ]; then
        printf 'release APK manifest must contain exactly one %s resource reference\n' "$attribute" >&2
        exit 1
    fi
    resource_id="$(sed -E 's/.*@ref\/(0x[[:xdigit:]]+)".*/\1/' "$attribute_lines")"
    if ! grep -E "^[[:space:]]*resource $resource_id $resource_name$" "$packaged_resources" >/dev/null; then
        printf 'release APK manifest %s does not reference %s\n' "$attribute" "$resource_name" >&2
        exit 1
    fi
}
assert_manifest_resource dataExtractionRules xml/data_extraction_rules
assert_manifest_resource fullBackupContent xml/full_backup_content
assert_manifest_resource networkSecurityConfig xml/network_security_config

resolve_packaged_resource_path() {
    resource_name="$1"
    awk -v expected="$resource_name" '
        $1 == "resource" {
            if (matched) {
                exit
            }
            matched = ($3 == expected)
            next
        }
        matched && $2 == "(file)" {
            print $3
            count++
        }
        END { exit count == 1 ? 0 : 1 }
    ' "$packaged_resources"
}
if ! data_extraction_resource_path="$(resolve_packaged_resource_path xml/data_extraction_rules)"; then
    printf 'release APK must contain exactly one data extraction rules configuration\n' >&2
    exit 1
fi
if ! full_backup_resource_path="$(resolve_packaged_resource_path xml/full_backup_content)"; then
    printf 'release APK must contain exactly one legacy backup rules configuration\n' >&2
    exit 1
fi
if ! network_security_resource_path="$(resolve_packaged_resource_path xml/network_security_config)"; then
    printf 'release APK must contain exactly one network security configuration\n' >&2
    exit 1
fi

native_dex_method="$temporary_directory/native-call.dex.txt"
"$APKANALYZER" dex code \
    --class org.aurora.protocol.android.core.NativeCoreJni \
    --method 'nativeCall(I[BJ)[B' \
    "$APK" > "$native_dex_method"
if ! grep -E '^[.]method .* native nativeCall\(I\[BJ\)\[B$' "$native_dex_method" >/dev/null; then
    printf 'release APK does not retain the reviewed nativeCall JNI declaration\n' >&2
    exit 1
fi

expected_permissions="$temporary_directory/expected-permissions.txt"
actual_permissions="$temporary_directory/actual-permissions.txt"
printf '%s\n' \
    android.permission.FOREGROUND_SERVICE \
    android.permission.FOREGROUND_SERVICE_SPECIAL_USE \
    android.permission.INTERNET \
    android.permission.POST_NOTIFICATIONS \
    | LC_ALL=C sort > "$expected_permissions"
"$APKANALYZER" manifest permissions "$APK" | sed '/^[[:space:]]*$/d' | LC_ALL=C sort > "$actual_permissions"
if ! cmp -s "$expected_permissions" "$actual_permissions"; then
    printf 'release APK permission set does not match the reviewed manifest\n' >&2
    diff -u "$expected_permissions" "$actual_permissions" >&2 || true
    exit 1
fi

component_count="$(grep -Ec '^[[:space:]]*<(activity|activity-alias|service|receiver|provider)([[:space:]>]|$)' "$packaged_manifest")"
if [ "$component_count" -ne 2 ]; then
    printf 'release APK manifest has an unexpected component surface: %s components\n' "$component_count" >&2
    exit 1
fi
activity_manifest="$temporary_directory/activity-manifest.xml"
service_manifest="$temporary_directory/service-manifest.xml"
awk '/<activity([[:space:]>]|$)/ { capture = 1 } capture { print } capture && /<\/activity>/ { exit }' \
    "$packaged_manifest" > "$activity_manifest"
awk '/<service([[:space:]>]|$)/ { capture = 1 } capture { print } capture && /<\/service>/ { exit }' \
    "$packaged_manifest" > "$service_manifest"
for required_activity_attribute in \
    'android:name="org.aurora.protocol.android.AuroraActivity"' \
    'android:exported="true"' \
    'android:name="android.intent.action.MAIN"' \
    'android:name="android.intent.category.LAUNCHER"'; do
    grep -F "$required_activity_attribute" "$activity_manifest" >/dev/null
done
for required_service_attribute in \
    'android:name="org.aurora.protocol.android.AuroraVpnService"' \
    'android:exported="true"' \
    'android:foregroundServiceType="0x40000000"' \
    'android:permission="android.permission.BIND_VPN_SERVICE"' \
    'android:name="android.net.VpnService"' \
    'android:name="android.net.VpnService.SUPPORTS_ALWAYS_ON"' \
    'android:value="false"' \
    'android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"' \
    'android:value="User-initiated encrypted VPN tunnel"'; do
    grep -F "$required_service_attribute" "$service_manifest" >/dev/null
done
assert_component_count() {
    label="$1"
    expected="$2"
    pattern="$3"
    file="$4"
    actual="$(grep -Ec "$pattern" "$file" || true)"
    if [ "$actual" -ne "$expected" ]; then
        printf 'release APK %s mismatch: expected %s, got %s\n' "$label" "$expected" "$actual" >&2
        exit 1
    fi
}
assert_component_count activity-intent-filters 1 '^[[:space:]]*<intent-filter([[:space:]>]|$)' "$activity_manifest"
assert_component_count activity-actions 1 '^[[:space:]]*<action([[:space:]>]|$)' "$activity_manifest"
assert_component_count activity-categories 1 '^[[:space:]]*<category([[:space:]>]|$)' "$activity_manifest"
assert_component_count activity-data-elements 0 '^[[:space:]]*<data([[:space:]>]|$)' "$activity_manifest"
assert_component_count service-intent-filters 1 '^[[:space:]]*<intent-filter([[:space:]>]|$)' "$service_manifest"
assert_component_count service-actions 1 '^[[:space:]]*<action([[:space:]>]|$)' "$service_manifest"
assert_component_count service-categories 0 '^[[:space:]]*<category([[:space:]>]|$)' "$service_manifest"
assert_component_count service-data-elements 0 '^[[:space:]]*<data([[:space:]>]|$)' "$service_manifest"
assert_component_count service-meta-data 1 '^[[:space:]]*<meta-data([[:space:]>]|$)' "$service_manifest"
assert_component_count service-properties 1 '^[[:space:]]*<property([[:space:]>]|$)' "$service_manifest"

data_extraction_rules="$temporary_directory/data-extraction-rules.xml"
full_backup_content="$temporary_directory/full-backup-content.xml"
network_security_config="$temporary_directory/network-security-config.xml"
"$APKANALYZER" resources xml --file "/$data_extraction_resource_path" "$APK" > "$data_extraction_rules"
"$APKANALYZER" resources xml --file "/$full_backup_resource_path" "$APK" > "$full_backup_content"
"$APKANALYZER" resources xml --file "/$network_security_resource_path" "$APK" > "$network_security_config"

assert_xml_count() {
    label="$1"
    expected="$2"
    pattern="$3"
    file="$4"
    actual="$(grep -Ec "$pattern" "$file" || true)"
    if [ "$actual" -ne "$expected" ]; then
        printf 'release APK %s mismatch: expected %s, got %s\n' "$label" "$expected" "$actual" >&2
        exit 1
    fi
}
assert_xml_count data-extraction-excludes 18 '^[[:space:]]*<exclude([[:space:]>]|$)' "$data_extraction_rules"
assert_xml_count data-extraction-root-paths 18 '^[[:space:]]*path="\."' "$data_extraction_rules"
assert_xml_count legacy-backup-excludes 9 '^[[:space:]]*<exclude([[:space:]>]|$)' "$full_backup_content"
assert_xml_count legacy-backup-root-paths 9 '^[[:space:]]*path="\."' "$full_backup_content"
for backup_domain in root file database sharedpref external device_root device_file device_database device_sharedpref; do
    assert_xml_count "data-extraction-$backup_domain" 2 "^[[:space:]]*domain=\"$backup_domain\"" "$data_extraction_rules"
    assert_xml_count "legacy-backup-$backup_domain" 1 "^[[:space:]]*domain=\"$backup_domain\"" "$full_backup_content"
done
assert_xml_count network-base-config 1 '^[[:space:]]*<base-config([[:space:]>]|$)' "$network_security_config"
assert_xml_count network-cleartext-policy 1 '^[[:space:]]*cleartextTrafficPermitted="false"' "$network_security_config"
if grep -E '<(domain-config|debug-overrides|trust-anchors|certificates|pin-set)([[:space:]>]|$)' \
    "$network_security_config" >/dev/null; then
    printf 'release APK network security config contains an unexpected policy override\n' >&2
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
if [ ! -x "$toolchain/llvm-readelf" ] || [ ! -x "$toolchain/llvm-nm" ]; then
    printf 'Android NDK host toolchain is unavailable: %s\n' "$toolchain" >&2
    exit 1
fi
if ! grep -Fx 'Pkg.Revision = 27.1.12297006' "$ANDROID_NDK_HOME/source.properties" >/dev/null 2>&1; then
    printf 'Android NDK does not match the pinned revision: %s\n' "$ANDROID_NDK_HOME" >&2
    exit 1
fi

expected_native_entries="$temporary_directory/expected-native-entries.txt"
actual_native_entries="$temporary_directory/actual-native-entries.txt"
all_package_entries="$temporary_directory/all-package-entries.txt"
duplicate_package_entries="$temporary_directory/duplicate-package-entries.txt"
unzip -Z1 "$APK" > "$all_package_entries"
LC_ALL=C sort "$all_package_entries" | uniq -d > "$duplicate_package_entries"
if [ -s "$duplicate_package_entries" ]; then
    printf 'release APK contains duplicate ZIP entries\n' >&2
    cat "$duplicate_package_entries" >&2
    exit 1
fi
if awk '$0 ~ /^\// || $0 ~ /(^|\/)\.\.(\/|$)/ { found = 1 } END { exit found ? 0 : 1 }' \
    "$all_package_entries"; then
    printf 'release APK contains an unsafe ZIP entry path\n' >&2
    exit 1
fi
printf '%s\n' \
    'lib/arm64-v8a/libaurora_android_jni.so' \
    'lib/arm64-v8a/libauroracore.so' \
    'lib/x86_64/libaurora_android_jni.so' \
    'lib/x86_64/libauroracore.so' \
    | LC_ALL=C sort > "$expected_native_entries"
awk '$0 ~ "^lib/[^/]+/[^/]+[.]so$" { print }' "$all_package_entries" \
    | LC_ALL=C sort > "$actual_native_entries"
if ! cmp -s "$expected_native_entries" "$actual_native_entries"; then
    printf 'release APK native library set does not match the supported ABI manifest\n' >&2
    diff -u "$expected_native_entries" "$actual_native_entries" >&2 || true
    exit 1
fi

trust_entry='assets/AuroraSignedSeedTrust.bin'
trust_entry_count="$(awk -v expected="$trust_entry" '$0 == expected { count++ } END { print count + 0 }' "$all_package_entries")"
if [ "$trust_entry_count" -ne 1 ]; then
    printf 'release APK must contain exactly one sealed native trust resource\n' >&2
    exit 1
fi
packaged_trust="$temporary_directory/AuroraSignedSeedTrust.bin"
unzip -p "$APK" "$trust_entry" > "$packaged_trust"
packaged_trust_sha256="$(sha256_file "$packaged_trust")"
if [ "$packaged_trust_sha256" != "$EXPECTED_TRUST_SHA256" ]; then
    printf 'packaged native trust resource does not match the reviewed release digest\n' >&2
    printf '  expected: %s\n' "$EXPECTED_TRUST_SHA256" >&2
    printf '  actual:   %s\n' "$packaged_trust_sha256" >&2
    exit 1
fi
if ! cmp -s "$TRUST_RESOURCE" "$packaged_trust"; then
    printf 'packaged native trust resource differs from the validated source asset\n' >&2
    exit 1
fi

"$ZIPALIGN" -c -P 16 4 "$APK"

verify_library() {
    abi="$1"
    name="$2"
    expected_soname="$3"
    expected_machine="$4"
    entry="lib/$abi/$name"
    library="$temporary_directory/$abi-$name"
    elf_header="$temporary_directory/$abi-$name.elf-header.txt"
    dynamic="$temporary_directory/$abi-$name.dynamic.txt"
    program_headers="$temporary_directory/$abi-$name.program-headers.txt"
    section_headers="$temporary_directory/$abi-$name.section-headers.txt"
    alignments="$temporary_directory/$abi-$name.alignments.txt"
    symbols="$temporary_directory/$abi-$name.symbols.txt"
    expected_interface="$temporary_directory/$abi-$name.expected-interface.txt"
    actual_interface="$temporary_directory/$abi-$name.actual-interface.txt"
    expected_dependencies="$temporary_directory/$abi-$name.expected-dependencies.txt"
    actual_dependencies="$temporary_directory/$abi-$name.actual-dependencies.txt"
    build_info="$temporary_directory/$abi-$name.go-build-info.txt"

    unzip -p "$APK" "$entry" > "$library"
    test -s "$library"
    compression="$(unzip -lv "$APK" "$entry" | awk -v expected="$entry" '$NF == expected { print $2 }')"
    if [ "$compression" != "Stored" ]; then
        printf 'release native library is compressed instead of directly loadable: %s\n' "$entry" >&2
        exit 1
    fi

    "$toolchain/llvm-readelf" -hW "$library" > "$elf_header"
    grep -F 'Class:                             ELF64' "$elf_header" >/dev/null
    grep -F 'Data:                              2' "$elf_header" | grep -F 'little endian' >/dev/null
    grep -F 'Type:                              DYN (Shared object file)' "$elf_header" >/dev/null
    grep -F "Machine:                           $expected_machine" "$elf_header" >/dev/null
    "$toolchain/llvm-readelf" -SW "$library" > "$section_headers"
    if grep -E '[.](debug|zdebug)_[[:alnum:]_.]+|[.]symtab([[:space:]]|$)' \
        "$section_headers" >/dev/null; then
        printf 'release native library retains debug or static symbol sections: %s\n' "$entry" >&2
        exit 1
    fi

    "$toolchain/llvm-nm" -D --defined-only "$library" > "$symbols"
    case "$name" in
        libauroracore.so)
            for required_symbol in AuroraCoreCall AuroraCoreFree AuroraCoreZeroFree; do
                grep -E " $required_symbol$" "$symbols" >/dev/null
            done
            printf '%s\n' AuroraCoreCall AuroraCoreFree AuroraCoreZeroFree \
                | LC_ALL=C sort > "$expected_interface"
            awk '$NF ~ /^AuroraCore/ { print $NF }' "$symbols" \
                | LC_ALL=C sort > "$actual_interface"
            printf '%s\n' libc.so libdl.so liblog.so \
                | LC_ALL=C sort > "$expected_dependencies"
            GOTOOLCHAIN="$GO_TOOLCHAIN" \
                GOENV=off \
                GOWORK=off \
                GODEBUG= \
                GOEXPERIMENT= \
                GOFIPS140=off \
                go version -m "$library" > "$build_info"
            grep -E ': go1[.]26[.]6$' "$build_info" >/dev/null
            awk '$1 == "path" && $2 == "github.com/aurora-protocol/aurora-core/mobile/auroracore" { found = 1 } END { exit found ? 0 : 1 }' \
                "$build_info"
            for required_build_setting in \
                '-buildmode=c-shared' \
                '-compiler=gc' \
                '-trimpath=true' \
                'CGO_ENABLED=1' \
                'GOOS=android' \
                'vcs=git' \
                "vcs.revision=$EXPECTED_CORE_REVISION" \
                'vcs.modified=false'; do
                awk -v expected="$required_build_setting" \
                    '$1 == "build" && $2 == expected { found = 1 } END { exit found ? 0 : 1 }' \
                    "$build_info"
            done
            case "$abi" in
                arm64-v8a)
                    required_goarch='GOARCH=arm64'
                    required_architecture_setting='GOARM64=v8.0'
                    ;;
                x86_64)
                    required_goarch='GOARCH=amd64'
                    required_architecture_setting='GOAMD64=v1'
                    ;;
            esac
            for required_build_setting in "$required_goarch" "$required_architecture_setting"; do
                awk -v expected="$required_build_setting" \
                    '$1 == "build" && $2 == expected { found = 1 } END { exit found ? 0 : 1 }' \
                    "$build_info"
            done
            ;;
        libaurora_android_jni.so)
            grep -E ' Java_org_aurora_protocol_android_core_NativeCoreJni_nativeCall$' "$symbols" >/dev/null
            printf '%s\n' Java_org_aurora_protocol_android_core_NativeCoreJni_nativeCall \
                > "$expected_interface"
            awk '$NF ~ /^Java_/ { print $NF }' "$symbols" \
                | LC_ALL=C sort > "$actual_interface"
            printf '%s\n' libauroracore.so libc.so libdl.so liblog.so libm.so \
                | LC_ALL=C sort > "$expected_dependencies"
            ;;
    esac
    if ! cmp -s "$expected_interface" "$actual_interface"; then
        printf 'release native library public interface differs from the reviewed ABI: %s\n' "$entry" >&2
        diff -u "$expected_interface" "$actual_interface" >&2 || true
        exit 1
    fi

    "$toolchain/llvm-readelf" -dW "$library" > "$dynamic"
    grep -F "Library soname: [$expected_soname]" "$dynamic" >/dev/null
    sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p' "$dynamic" \
        | LC_ALL=C sort > "$actual_dependencies"
    if ! cmp -s "$expected_dependencies" "$actual_dependencies"; then
        printf 'release native library dependency set differs from the reviewed ABI: %s\n' "$entry" >&2
        diff -u "$expected_dependencies" "$actual_dependencies" >&2 || true
        exit 1
    fi
    if grep -E '(TEXTREL|FLAGS[^]]*TEXTREL)' "$dynamic" >/dev/null; then
        printf 'release native library contains text relocations: %s\n' "$entry" >&2
        exit 1
    fi
    if grep -E '\((RPATH|RUNPATH)\)' "$dynamic" >/dev/null; then
        printf 'release native library contains a runtime search path: %s\n' "$entry" >&2
        exit 1
    fi
    grep -F 'BIND_NOW' "$dynamic" >/dev/null

    "$toolchain/llvm-readelf" -lW "$library" > "$program_headers"
    grep -F 'GNU_RELRO' "$program_headers" >/dev/null
    if ! awk '$1 == "GNU_STACK" && $(NF - 1) == "RW" { count++ } END { exit count == 1 ? 0 : 1 }' \
        "$program_headers"; then
        printf 'release native library lacks exactly one non-executable GNU_STACK header: %s\n' "$entry" >&2
        exit 1
    fi
    awk '$1 == "LOAD" { print $NF }' "$program_headers" > "$alignments"
    test -s "$alignments"
    if grep -Fvx '0x4000' "$alignments" >/dev/null; then
        printf 'release native library has a LOAD segment without 16 KiB alignment: %s\n' "$entry" >&2
        exit 1
    fi
}

verify_library arm64-v8a libauroracore.so libauroracore.so AArch64
verify_library arm64-v8a libaurora_android_jni.so libaurora_android_jni.so AArch64
verify_library x86_64 libauroracore.so libauroracore.so 'Advanced Micro Devices X86-64'
verify_library x86_64 libaurora_android_jni.so libaurora_android_jni.so 'Advanced Micro Devices X86-64'

printf 'release_native_package_check passed=true abis=2 page_size=16384\n'
