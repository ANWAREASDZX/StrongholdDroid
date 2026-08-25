#!/usr/bin/env bash
#
# setup_toolchain.sh — installs the cross-compilation toolchain needed to
# build Wine, Box64, DXVK, and gl4es for Android arm64-v8a.
#
# Run this ONCE on a fresh dev box (Linux x86_64). After it completes,
# the other build_*.sh scripts can run in any order.
#
# Tested on:
#   • Ubuntu 22.04 x86_64
#   • Debian 12 x86_64
#   • macOS 14 (via Docker)
#
# Prerequisites already present on a fresh Ubuntu 22.04:
#   sudo apt install -y build-essential git wget curl unzip python3-pip \
#                       pkg-config mingw-w64 ccache
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"

print_banner "Setting up StrongholdDroid toolchain"

# ----------------------------------------------------------------------------
# 1. Android NDK — pick the exact version pinned in app/build.gradle.kts
# ----------------------------------------------------------------------------
NDK_VERSION="${NDK_VERSION:-26.1.10909125}"
NDK_DIR="${NDK_DIR:-$HOME/Android/Sdk/ndk/$NDK_VERSION}"

if [[ ! -d "$NDK_DIR" ]]; then
    log "NDK $NDK_VERSION not found at $NDK_DIR — downloading..."
    mkdir -p "$(dirname "$NDK_DIR")"
    # Avoid sdkmanager dependency — direct download from the official mirror
    case "$(uname -s)" in
        Linux*)  NDK_ZIP_URL="https://dl.google.com/android/repository/android-ndk-r$NDK_VERSION-linux.zip";;
        Darwin*) NDK_ZIP_URL="https://dl.google.com/android/repository/android-ndk-r$NDK_VERSION-darwin.zip";;
        *) die "Unsupported host OS: $(uname -s)";;
    esac
    # The official download URL is more complex; fall back to sdkmanager.
    if command -v sdkmanager &> /dev/null; then
        yes | sdkmanager --install "ndk;$NDK_VERSION" || \
            die "Failed to install NDK via sdkmanager"
    else
        die "Either place NDK at $NDK_DIR manually or install sdkmanager"
    fi
fi
export ANDROID_NDK_HOME="$NDK_DIR"
log_ok "NDK: $ANDROID_NDK_HOME"

# ----------------------------------------------------------------------------
# 2. Cross toolchain symlinks — NDK ships aarch64-linux-android-* directly
# ----------------------------------------------------------------------------
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$(uname -s)-x86_64/bin:$PATH"
command -v aarch64-linux-android26-clang || die "aarch64 clang not found after NDK install"
log_ok "aarch64 clang available"

# ----------------------------------------------------------------------------
# 3. Meson + Ninja — Wine 8+ and gl4es prefer Meson over autoconf
# ----------------------------------------------------------------------------
if ! command -v meson &> /dev/null; then
    pip3 install --user meson==1.4.0
fi
if ! command -v ninja &> /dev/null; then
    pip3 install --user ninja==1.12.1
fi
log_ok "meson ($(meson --version)) + ninja ($(ninja --version))"

# ----------------------------------------------------------------------------
# 4. Mingw-w64 — needed for DXVK (compiles Windows DLLs via MinGW)
# ----------------------------------------------------------------------------
if ! command -v x86_64-w64-mingw32-gcc &> /dev/null; then
    case "$(uname -s)" in
        Linux*)
            sudo apt install -y mingw-w64 mingw-w64-tools
            ;;
        Darwin*)
            brew install mingw-w64
            ;;
    esac
fi
log_ok "mingw-w64: $(x86_64-w64-mingw32-gcc --version | head -1)"

# ----------------------------------------------------------------------------
# 5. Wine build deps — Flex, bison, xorg dev headers, freetype, gstreamer
# ----------------------------------------------------------------------------
if [[ "$(uname -s)" == "Linux" ]] && ! command -v flex &> /dev/null; then
    sudo apt install -y \
        flex bison \
        libfreetype-dev libfreetype6-dev \
        libx11-dev libxext-dev libxcomposite-dev libxrandr-dev \
        libxrender-dev libxcursor-dev libxinerama-dev libxi-dev \
        libxfixes-dev libxxf86vm-dev libxv-dev \
        libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev \
        libpulse-dev libasound2-dev
fi
log_ok "Wine build dependencies present"

# ----------------------------------------------------------------------------
# 6. CMake — NDK ships 3.22.1 but DXVK now requires 3.25+
# ----------------------------------------------------------------------------
if ! command -v cmake &> /dev/null || [[ "$(cmake --version | head -1)" < "cmake version 3.25" ]]; then
    log "Installing CMake 3.30+ from PyPI"
    pip3 install --user cmake==3.30.3
fi
log_ok "cmake: $(cmake --version | head -1)"

# ----------------------------------------------------------------------------
# 7. PatchELF — needed to shrink our bundled Wine binaries
# ----------------------------------------------------------------------------
if ! command -v patchelf &> /dev/null; then
    case "$(uname -s)" in
        Linux*)  sudo apt install -y patchelf;;
        Darwin*) brew install patchelf;;
    esac
fi
log_ok "patchelf: $(patchelf --version)"

print_banner "Toolchain ready ✓"
echo
echo "Next steps:"
echo "  ./scripts/build_all.sh         # builds Wine + Box64 + DXVK + gl4es"
echo "  ./scripts/build_apk.sh         # assembles the APK"
echo
