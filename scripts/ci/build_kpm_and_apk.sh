#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

: "${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT is required}"
KP_DIR="${KP_DIR:-$ROOT_DIR/KernelPatch}"
NDK_VERSION="${NDK_VERSION:-27.0.12077973}"
GRADLE_VERSION="${GRADLE_VERSION:-8.7}"
GRADLE_BIN="${GRADLE_BIN:-}"

if [[ ! -d "$KP_DIR" ]]; then
  echo "[ERROR] KP_DIR does not exist: $KP_DIR" >&2
  exit 1
fi

ensure_gradle() {
  if [[ -n "$GRADLE_BIN" ]]; then
    return 0
  fi

  local sys_gradle="${SYS_GRADLE_BIN:-gradle}"
  if command -v "$sys_gradle" >/dev/null 2>&1; then
    local gv major
    gv="$("$sys_gradle" --version | sed -nE 's/^Gradle ([0-9]+([.][0-9]+){0,2}).*/\1/p' | head -n 1)"
    major=""
    if [[ "$gv" =~ ^([0-9]+)([.].*)?$ ]]; then
      major="${BASH_REMATCH[1]}"
    fi
    if [[ -n "$major" ]] && (( major < 9 )); then
      GRADLE_BIN="$sys_gradle"
      return 0
    fi
    echo "[INFO] Detected Gradle ${gv:-unknown}; using pinned Gradle $GRADLE_VERSION for AGP compatibility."
  else
    echo "[INFO] Gradle not found; downloading Gradle $GRADLE_VERSION."
  fi

  local tools_dir="$ROOT_DIR/.build_tools"
  local gradle_dir="$tools_dir/gradle-$GRADLE_VERSION"
  local zip="$tools_dir/gradle-$GRADLE_VERSION-bin.zip"
  mkdir -p "$tools_dir"
  if [[ ! -x "$gradle_dir/bin/gradle" ]]; then
    curl -fsSL -o "$zip" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    rm -rf "$gradle_dir"
    unzip -q "$zip" -d "$tools_dir"
  fi
  GRADLE_BIN="$gradle_dir/bin/gradle"
}

ensure_gradle

NDK_PREBUILT_DIR="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION/toolchains/llvm/prebuilt"
TOOLCHAIN_BIN="$(find "$NDK_PREBUILT_DIR" -maxdepth 2 -type d -name bin | head -n 1 || true)"

if [[ -z "$TOOLCHAIN_BIN" ]]; then
  echo "[ERROR] NDK toolchain not found under: $NDK_PREBUILT_DIR" >&2
  exit 1
fi

echo "[1/2] Building KPM with toolchain: $TOOLCHAIN_BIN"
make -C "$ROOT_DIR/kpm" clean all \
  KP_DIR="$KP_DIR" \
  SDK_DIR="$ANDROID_SDK_ROOT" \
  NDK_VERSION="$NDK_VERSION" \
  TOOLCHAIN="$TOOLCHAIN_BIN"

KPM_OUT="$ROOT_DIR/kpm/svc_monitor.kpm"
if [[ ! -f "$KPM_OUT" ]]; then
  echo "[ERROR] Missing KPM artifact: $KPM_OUT" >&2
  exit 1
fi

echo "[2/2] Building Android APK"
(
  cd "$ROOT_DIR/android"
  echo "[INFO] Gradle binary: $GRADLE_BIN"
  "$GRADLE_BIN" -p . :app:assembleDebug --no-daemon -Dkotlin.daemon.enabled=false
)

APK_OUT="$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK_OUT" ]]; then
  echo "[ERROR] Missing APK artifact: $APK_OUT" >&2
  exit 1
fi

echo "[OK] Built artifacts:"
echo "- $KPM_OUT"
echo "- $APK_OUT"
