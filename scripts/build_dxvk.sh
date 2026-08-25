#!/usr/bin/env bash
#
# build_dxvk.sh — build DXVK 2.x and its associated MinGW Windows DLLs
# (d3d9.dll, d3d10core.dll, d3d11.dll, dxgi.dll).
#
# DXVK is the heart of the graphics pipeline for SC HD / Extreme —
# it converts DirectX 9/10/11 calls to Vulkan on the fly.
#
# Build strategy:
#   1. Wine's MinGW headers (we just built) are required to compile DXVK
#   2. DXVK ships its own Vulkan-Hpp + MinGW headers for self-containment
#   3. Cross-compile with x86_64-w64-mingw32-gcc — produces Windows .dll
#   4. The Vulkan *loader* (libvulkan.so) is built separately as a normal
#      Android shared lib (we don't ship the full SDK — only the loader)
#
# Output:
#   prebuilt/arm64-v8a/libdxvk_loader.so        ← the Android-side Vulkan loader
#   prebuilt/arm64-v8a/dxvk-wine-dlls/*.dll     ← native Windows DLLs staged
#                                                   for the WINEPREFIX
#
# Build time: ~25 min (DXVK is huge).
#
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"
print_banner "Building DXVK 2.x + Vulkan Loader"

setup_android_env

DXVK_VERSION="${DXVK_VERSION:-v2.4.1}"
DXVK_SRC="$BUILD_DIR/dxvk-$DXVK_VERSION"
DXVK_BUILD="$BUILD_DIR/dxvk-mingw-x86_64"

# ---- 1. DXVK source (MinGW path) -------------------------------------------
if [[ ! -d "$DXVK_SRC/.git" ]]; then
    log "Cloning DXVK $DXVK_VERSION..."
    git clone --depth 1 --branch "$DXVK_VERSION" \
        https://github.com/doitsujin/dxvk.git "$DXVK_SRC"
fi

# ---- 2. Build DXVK as MinGW Windows DLLs ----------------------------------
log "Building DXVK MinGW DLLs..."
mkdir -p "$DXVK_BUILD"
cd "$DXVK_BUILD"

# DXVK ships a build-wine64.sh script that wraps meson + mingw
"$DXVK_SRC/package-release.sh" \
    --build-dir "$DXVK_BUILD" \
    --no-package \
    --destdir "$DXVK_BUILD/install" \
    1>&2  # script uses bashdb-style logging

# Collect the dlls
DXVK_DLL_OUT="$PREBUILT_DIR/arm64-v8a/dxvk-wine-dlls"
mkdir -p "$DXVK_DLL_OUT"
for dll in d3d9 d3d10core d3d11 dxgi d3dcompiler_47; do
    src="$DXVK_BUILD/install/usr/lib/wine/x86_64-windows/$dll.dll"
    if [[ -f "$src" ]]; then
        cp -f "$src" "$DXVK_DLL_OUT/"
        log "  installed: $dll.dll"
    else
        warn "missing $dll.dll from DXVK build"
    fi
done

# ---- 3. Vulkan Loader (Android-side libvulkan.so shim) ---------------------
# The Android system already provides libvulkan.so on Vulkan 1.1+ devices.
# We do NOT ship a replacement. Instead, we ship a *loader* that DXVK
# links against that resolves to the system Vulkan at runtime via dlopen.
# This way DXVK's PE binaries can be MinGW-compiled without depending
# on Android's libvulkan directly.

VULKAN_LOADER_SRC="$BUILD_DIR/vulkan-loader-android"
if [[ ! -d "$VULKAN_LOADER_SRC/.git" ]]; then
    log "Cloning Vulkan-Loader shim..."
    git clone --depth 1 --branch sdk-1.3.283 \
        https://github.com/KhronosGroup/Vulkan-Loader.git "$VULKAN_LOADER_SRC"
fi

VULKAN_LOADER_BUILD="$BUILD_DIR/vulkan-loader-android-arm64"
mkdir -p "$VULKAN_LOADER_BUILD"
cd "$VULKAN_LOADER_BUILD"

cmake "$VULKAN_LOADER_SRC" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="arm64-v8a" \
    -DANDROID_PLATFORM="android-26" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_TESTS=OFF \
    -DUPDATE_DEPS=OFF
make -j"$(nproc)"

install_lib "$VULKAN_LOADER_BUILD/loader/libvulkan.so" "libdxvk_loader.so"

log_ok "DXVK + Vulkan loader built"
print_banner "DXVK build complete ✓"
