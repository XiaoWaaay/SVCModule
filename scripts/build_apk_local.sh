#!/usr/bin/env bash
set -euo pipefail

# -------------------------------------------------------------------
# Single script to set up Android SDK + JDK 17 + Gradle 8.7,
# then build the APK (KPM module not required).
# Matches versions from your GitHub workflow:
#   - Android platform 34, build-tools 34.0.0
#   - Gradle 8.7
#   - JDK 17
# -------------------------------------------------------------------

# ----------------------- Configuration -----------------------------
SDK_INSTALL_DIR="${SDK_INSTALL_DIR:-/opt/android-sdk}"
PLATFORM_VERSION="android-34"
BUILD_TOOLS_VERSION="34.0.0"
GRADLE_VERSION="8.7"
JAVA_VERSION="17"

# ----------------------- Helper Functions --------------------------
log_info() { echo "[INFO] $*"; }
log_error() { echo "[ERROR] $*" >&2; exit 1; }

# Find the project root (where android/ directory lives)
find_project_root() {
    local dir="$PWD"
    while [[ "$dir" != "/" ]]; do
        if [[ -d "$dir/android" ]] && [[ -d "$dir/kpm" ]]; then
            echo "$dir"
            return
        fi
        dir="$(dirname "$dir")"
    done
    # If not found, assume current directory is root
    echo "$PWD"
}

PROJECT_ROOT="$(find_project_root)"
log_info "Project root: $PROJECT_ROOT"

# ----------------------- JDK 17 ------------------------------------
ensure_jdk17() {
    if command -v java >/dev/null 2>&1; then
        local java_version
        java_version="$(java -version 2>&1 | head -n1 | grep -oP 'version "?\K[0-9]+' || echo "0")"
        if [[ "$java_version" -ge 17 ]]; then
            log_info "JDK $java_version already available."
            return
        fi
    fi
    log_info "Installing OpenJDK 17..."
    sudo apt update -qq
    sudo apt install -y openjdk-17-jdk wget unzip curl
}

# ----------------------- Gradle 8.7 --------------------------------
setup_gradle() {
    local gradle_bin=""
    if command -v gradle >/dev/null 2>&1; then
        local gv
        gv="$(gradle --version | grep -E '^Gradle' | awk '{print $2}')"
        local major="${gv%%.*}"
        if [[ "$major" -eq 8 ]] && [[ "$gv" == "$GRADLE_VERSION" ]]; then
            gradle_bin="gradle"
            log_info "Using system Gradle $gv"
        else
            log_info "System Gradle $gv != $GRADLE_VERSION; will download pinned version."
        fi
    fi

    if [[ -z "$gradle_bin" ]]; then
        local tools_dir="$PROJECT_ROOT/.build_tools"
        local gradle_dir="$tools_dir/gradle-$GRADLE_VERSION"
        local zip="$tools_dir/gradle-$GRADLE_VERSION-bin.zip"
        mkdir -p "$tools_dir"
        if [[ ! -x "$gradle_dir/bin/gradle" ]]; then
            log_info "Downloading Gradle $GRADLE_VERSION..."
            curl -fsSL -o "$zip" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
            rm -rf "$gradle_dir"
            unzip -q "$zip" -d "$tools_dir"
        fi
        gradle_bin="$gradle_dir/bin/gradle"
    fi
    echo "$gradle_bin"
}

# ----------------------- Android SDK --------------------------------
find_existing_sdk() {
    # Check environment variable first
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]] && [[ -d "$ANDROID_SDK_ROOT" ]]; then
        echo "$ANDROID_SDK_ROOT"
        return 0
    fi
    # Check common locations
    local candidates=(
        "/home/$USER/Android/Sdk"
        "/opt/android-sdk"
        "/usr/lib/android-sdk"
        "$HOME/Android/Sdk"
    )
    for dir in "${candidates[@]}"; do
        if [[ -d "$dir" ]] && [[ -x "$dir/cmdline-tools/latest/bin/sdkmanager" ]]; then
            echo "$dir"
            return 0
        fi
    done
    return 1
}

install_sdk() {
    log_info "Android SDK not found. Installing into $SDK_INSTALL_DIR ..."
    sudo mkdir -p "$SDK_INSTALL_DIR"
    sudo chown "$USER:$USER" "$SDK_INSTALL_DIR"

    cd "$SDK_INSTALL_DIR"
    local tools_url="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    wget -q --show-progress "$tools_url"
    
    # Create temporary extraction directory
    local tmp_dir="cmdline-tools-tmp"
    mkdir -p "$tmp_dir"
    unzip -q commandlinetools-linux-*.zip -d "$tmp_dir"
    
    # Create the required nested structure: cmdline-tools/latest/
    mkdir -p cmdline-tools
    # Move contents from tmp_dir/cmdline-tools/ into cmdline-tools/latest/
    if [[ -d "$tmp_dir/cmdline-tools" ]]; then
        mv "$tmp_dir/cmdline-tools" cmdline-tools/latest
    else
        # Fallback: if zip extracts directly, move everything
        mv "$tmp_dir" cmdline-tools/latest
    fi
    
    rm -rf "$tmp_dir" commandlinetools-linux-*.zip

    local sdkmanager="$SDK_INSTALL_DIR/cmdline-tools/latest/bin/sdkmanager"
    # Make sdkmanager executable (just in case)
    chmod +x "$sdkmanager"
    
    log_info "Installing platform-tools, build-tools $BUILD_TOOLS_VERSION, platform $PLATFORM_VERSION ..."
    "$sdkmanager" "platform-tools" "build-tools;$BUILD_TOOLS_VERSION" "platforms;$PLATFORM_VERSION" >/dev/null
    log_info "Accepting all SDK licenses..."
    yes | "$sdkmanager" --licenses >/dev/null 2>&1

    log_info "SDK installation completed at $SDK_INSTALL_DIR"
}

setup_environment() {
    export ANDROID_SDK_ROOT="$1"
    export ANDROID_HOME="$1"
    export PATH="$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"
    log_info "ANDROID_SDK_ROOT set to $ANDROID_SDK_ROOT"
    log_info "PATH updated for this session"
}

# ----------------------- Build APK ----------------------------------
build_apk() {
    local gradle_cmd
    gradle_cmd="$(setup_gradle)"
    log_info "Using Gradle: $gradle_cmd"

    cd "$PROJECT_ROOT/android"
    log_info "Cleaning and building APK..."
    "$gradle_cmd" clean :app:assembleDebug --no-daemon -Dkotlin.daemon.enabled=false

    local apk="$PROJECT_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
    if [[ -f "$apk" ]]; then
        log_info "APK built successfully: $apk"
    else
        log_error "APK not found at expected location: $apk"
    fi
}

# ----------------------- Main ---------------------------------------
main() {
    ensure_jdk17

    local sdk_path
    if sdk_path="$(find_existing_sdk)"; then
        log_info "Using existing Android SDK at $sdk_path"
    else
        install_sdk
        sdk_path="$SDK_INSTALL_DIR"
    fi

    setup_environment "$sdk_path"
    build_apk

    log_info "Done. APK location: $PROJECT_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
}

main
