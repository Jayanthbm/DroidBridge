#!/usr/bin/env bash
#
# Record the environment baseline that Phase 0 (P00) requires.
#
# Prints the host toolchain, the Android SDK inventory and the attached device's identity. It
# reads only; it installs nothing and changes nothing. The output is what goes into
# Docs/prototypes/P00-environment-report.md, so the report can be regenerated rather than
# hand-copied and drifting from reality.
#
# Usage: scripts/env-baseline.sh

set -uo pipefail

section() { printf '\n## %s\n\n' "$1"; }
kv() { printf '%-28s %s\n' "$1" "$2"; }

section "Host"
kv "macOS" "$(sw_vers -productVersion) ($(sw_vers -buildVersion))"
kv "Architecture" "$(uname -m)"
kv "Hostname class" "$(sysctl -n hw.model 2>/dev/null || echo unknown)"
kv "CPU cores" "$(sysctl -n hw.ncpu 2>/dev/null || echo unknown)"
kv "Memory (GB)" "$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 ))"

section "Apple toolchain"
kv "Xcode" "$(xcodebuild -version 2>/dev/null | head -1 || echo 'not found')"
kv "Xcode build" "$(xcodebuild -version 2>/dev/null | tail -1 || echo 'not found')"
kv "Developer dir" "$(xcode-select -p 2>/dev/null || echo 'not found')"
kv "Swift" "$(swift --version 2>/dev/null | head -1 || echo 'not found')"
kv "macOS SDK" "$(xcodebuild -showsdks 2>/dev/null | awk '/macOS [0-9]/{print $2; exit}' || echo 'not found')"
kv "xcodegen" "$(xcodegen --version 2>/dev/null || echo 'not installed')"

section "JVM toolchain"
kv "java (PATH)" "$(java -version 2>&1 | head -1 || echo 'not found')"
kv "JAVA_HOME" "${JAVA_HOME:-<unset>}"
kv "Installed JVMs" "$(/usr/libexec/java_home -V 2>&1 | grep -c '^ *[0-9]' || echo 0)"
kv "Gradle (PATH)" "$(gradle --version 2>/dev/null | awk '/^Gradle /{print $2; exit}' || echo 'not found')"

section "Android SDK"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
kv "SDK root" "$SDK"
if [[ -d "$SDK" ]]; then
    kv "Platforms" "$(ls "$SDK/platforms" 2>/dev/null | tr '\n' ' ')"
    kv "Build-tools" "$(ls "$SDK/build-tools" 2>/dev/null | tr '\n' ' ')"
    kv "cmdline-tools" "$(ls "$SDK/cmdline-tools" 2>/dev/null | tr '\n' ' ')"
    kv "adb" "$(adb version 2>/dev/null | head -1 || echo 'not found')"
else
    kv "Platforms" "SDK directory not found"
fi

section "Attached device"
if command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then
    p() { adb shell getprop "$1" 2>/dev/null | tr -d '\r'; }
    kv "Serial" "$(adb get-serialno 2>/dev/null)"
    kv "Manufacturer" "$(p ro.product.manufacturer)"
    kv "Model" "$(p ro.product.model)"
    kv "Device" "$(p ro.product.device)"
    kv "Android release" "$(p ro.build.version.release)"
    kv "SDK / API level" "$(p ro.build.version.sdk)"
    kv "Security patch" "$(p ro.build.version.security_patch)"
    kv "Build display id" "$(p ro.build.display.id)"
    kv "Build fingerprint" "$(p ro.build.fingerprint)"
    kv "ro.debuggable" "$(p ro.debuggable)"
    kv "Verified boot state" "$(p ro.boot.verifiedbootstate)"
    kv "Wireless debugging (adb_wifi_enabled)" "$(adb shell settings get global adb_wifi_enabled 2>/dev/null | tr -d '\r')"
    kv "Developer options enabled" "$(adb shell settings get global development_settings_enabled 2>/dev/null | tr -d '\r')"
else
    echo "no device attached (or adb unavailable)"
fi

section "Notes"
echo "- USB connected: yes (adb reports the device over USB)"
echo "- USB debugging: enabled (required for adb access; development only)"
echo "- Wireless debugging: see adb_wifi_enabled above"
echo "- The final acceptance test must run with USB disconnected and both debugging modes off."
