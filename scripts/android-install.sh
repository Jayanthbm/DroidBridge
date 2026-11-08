#!/usr/bin/env bash
#
# Build the Android debug APK and install it on the attached device.
#
# Development-only convenience. The shipped app must work with USB disconnected and both USB and
# wireless debugging disabled (AGENTS.md); this script is for the developer loop, not a runtime
# dependency.
#
# Usage: scripts/android-install.sh [--launch]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LAUNCH=0
[[ "${1:-}" == "--launch" ]] && LAUNCH=1

if ! command -v adb >/dev/null 2>&1; then
    echo "adb not found on PATH" >&2
    exit 1
fi

DEVICES="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
if [[ -z "$DEVICES" ]]; then
    echo "no device in 'device' state; check 'adb devices'" >&2
    exit 1
fi
if [[ "$(echo "$DEVICES" | wc -l | tr -d ' ')" -gt 1 ]]; then
    echo "more than one device attached; set ANDROID_SERIAL to choose" >&2
    echo "$DEVICES" >&2
    exit 1
fi

echo "=== device ==="
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk

echo "=== build ==="
"$REPO_ROOT/android/gradlew" -p "$REPO_ROOT/android" :app:assembleDebug

APK="$REPO_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
echo "=== install ==="
adb install -r "$APK"

if [[ "$LAUNCH" -eq 1 ]]; then
    echo "=== launch ==="
    adb shell am start -n com.droidbridge.app/.MainActivity
fi
