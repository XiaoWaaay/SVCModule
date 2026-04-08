#!/usr/bin/env bash
set -e

# -------------------------------------------------------------------
# Fast clean build – no downloads, no environment setup.
# Assumes Gradle 8.7 is cached in .build_tools and SDK is at /opt/android-sdk
# -------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Path to cached Gradle (from previous full run)
GRADLE_BIN="$PROJECT_ROOT/.build_tools/gradle-8.7/bin/gradle"

if [[ ! -x "$GRADLE_BIN" ]]; then
    echo "[ERROR] Cached Gradle not found at $GRADLE_BIN"
    echo "Please run ./build_apk_local.sh once to set up the cache."
    exit 1
fi

# Set Android SDK path (must match the one used before)
export ANDROID_SDK_ROOT="/opt/android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"

if [[ ! -d "$ANDROID_SDK_ROOT" ]]; then
    echo "[ERROR] Android SDK not found at $ANDROID_SDK_ROOT"
    exit 1
fi

cd "$PROJECT_ROOT/android_modified"

# Manually delete the app build folder (ensures a completely clean build)
rm -rf app/build

echo "[INFO] Performing clean build (no downloads)..."
"$GRADLE_BIN" clean :app:assembleDebug --no-daemon

# Generate timestamp for APK filename
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
ORIGINAL_APK="$PROJECT_ROOT/android_modified/app/build/outputs/apk/debug/app-debug.apk"
TIMESTAMPED_APK="$PROJECT_ROOT/android_modified/app/build/outputs/apk/debug/app-debug_$TIMESTAMP.apk"

if [[ -f "$ORIGINAL_APK" ]]; then
    cp "$ORIGINAL_APK" "$TIMESTAMPED_APK"
    echo "[SUCCESS] Build finished at $TIMESTAMP"
    echo "APK location: $TIMESTAMPED_APK"
else
    echo "[ERROR] Build failed – APK not found."
    exit 1
fi
