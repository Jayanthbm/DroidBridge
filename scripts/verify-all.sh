#!/usr/bin/env bash
#
# Verify both native shells and the shared protocol module from a clean state.
#
# This is the Phase 0 acceptance gate and the CI entry point. It is deliberately boring: every
# step either passes or the script exits non-zero. Nothing here installs anything or touches a
# device, so it is safe to run anywhere the toolchains exist.
#
# Usage: scripts/verify-all.sh [--skip-macos]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKIP_MACOS=0

for arg in "$@"; do
    case "$arg" in
        --skip-macos) SKIP_MACOS=1 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

step() { printf '\n=== %s ===\n' "$1"; }

step "Shared protocol (JVM, platform-independent)"
"$REPO_ROOT/shared/protocol/gradlew" -p "$REPO_ROOT/shared/protocol" test

step "Android: assemble + unit tests + lint"
if [[ -f "$REPO_ROOT/android/local.properties" ]]; then
    ANDROID_ARGS=()
else
    # CI has no local.properties; the SDK comes from ANDROID_HOME/ANDROID_SDK_ROOT.
    ANDROID_ARGS=()
fi
"$REPO_ROOT/android/gradlew" -p "$REPO_ROOT/android" \
    :app:assembleDebug :app:testDebugUnitTest :app:lintDebug "${ANDROID_ARGS[@]+"${ANDROID_ARGS[@]}"}"

if [[ "$SKIP_MACOS" -eq 1 ]]; then
    step "macOS: skipped (--skip-macos)"
else
    step "macOS: build + unit tests"
    if ! command -v xcodebuild >/dev/null 2>&1; then
        echo "xcodebuild not found; macOS verification requires Xcode" >&2
        exit 1
    fi
    # The project is generated, not hand-edited, so regenerate before building. This keeps the
    # checked-in .xcodeproj honest against project.yml.
    if command -v xcodegen >/dev/null 2>&1; then
        ( cd "$REPO_ROOT/macos" && xcodegen generate )
    else
        echo "xcodegen not found; building the checked-in project as-is" >&2
    fi
    xcodebuild -project "$REPO_ROOT/macos/DroidBridge.xcodeproj" \
        -scheme DroidBridge -configuration Debug \
        -destination 'platform=macOS,arch=arm64' test
fi

step "All verifications passed"
