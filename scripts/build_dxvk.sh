#!/usr/bin/env bash
#
# build_dxvk.sh — build DXVK 2.x MinGW Windows DLLs (32-bit).
#
# DXVK is the graphics fast-path for D3D8/9 games (d3d8.dll, d3d9.dll,
# dxgi.dll).  Stronghold Crusader itself is DirectDraw (wined3d → gl4es),
# but SC HD/Extreme mods and other RTS titles of the era can use D3D9 —
# we ship the 32-bit DXVK dlls so wine's WINEDLLOVERRIDES can pick them.
#
# IMPORTANT: we build the x86 (32-bit) variant ONLY:
#   • The Windows side of our runtime is 32-bit — Stronghold Crusader is
#     a 32-bit x86 app running through wine's WoW64 layer (see
#     build_wine.sh: --enable-archs=aarch64,i386 + box64's wow64 cpu dll).
#     A 32-bit game can only ever load 32-bit d3d9.dll from syswow64.
#   • 64-bit Windows apps are not runnable in this architecture at all
#     (they'd need an arm64ec cpu.dll, which box64 does not ship yet),
#     so x64 DXVK dlls would be dead weight (halves the CI build time).
#
# Toolchain: i686-w64-mingw32-gcc — either Ubuntu's `mingw-w64` package
# (CI image) or llvm-mingw's gcc alias (bare dev boxes).
#
# Output:
#   prebuilt/arm64-v8a/dxvk-wine-dlls/*.dll  (32-bit PE, staged into the
#   WINEPREFIX's syswow64 at runtime by EnvironmentBuilder)
#
# Build time: ~6 min (x86 only).
#
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"
print_banner "Building DXVK 2.x (MinGW x86 32-bit DLLs)"

setup_android_env   # sets ANDROID_NDK_HOME etc. (DXVK itself doesn't need it,
                    # but keeping env consistent helps ccache + logging)

DXVK_VERSION="${DXVK_VERSION:-v2.4.1}"
DXVK_SRC="$BUILD_DIR/dxvk-$DXVK_VERSION"
DXVK_BUILD="$BUILD_DIR/dxvk-mingw-x86"
DXVK_STAGE="$DXVK_BUILD/dxvk-$DXVK_VERSION"

# ---- 1. DXVK source (WITH submodules!) --------------------------------------
if [[ ! -d "$DXVK_SRC/.git" ]]; then
    log "Cloning DXVK $DXVK_VERSION (with submodules)..."
    # --recurse-submodules is CRITICAL: include/vulkan (Vulkan-Headers),
    # include/native/directx (mingw-directx-headers), include/spirv
    # (SPIRV-Headers) and subprojects/libdisplay-info are submodules.
    # A plain clone leaves them empty and meson fails with
    # "vulkan/vulkan.h: No such file or directory".
    git clone --depth 1 --recurse-submodules --shallow-submodules \
        --branch "$DXVK_VERSION" \
        https://github.com/doitsujin/dxvk.git "$DXVK_SRC"
fi

# ---- 2. Sanity: mingw cross compiler present? -------------------------------
if ! command -v i686-w64-mingw32-gcc &> /dev/null; then
    die "i686-w64-mingw32-gcc not found. Install the 'mingw-w64' package
(Ubuntu: apt install mingw-w64 mingw-w64-tools) or llvm-mingw (see
scripts/docker/Dockerfile.build and scripts/lib/common.sh)."
fi
log_ok "mingw: $(i686-w64-mingw32-gcc --version | head -1)"

# ---- 3. Build DXVK x86-only -------------------------------------------------
# We invoke meson directly (mirroring package-release.sh's build_arch 32)
# so we can skip the x64 build entirely.
log "Configuring DXVK (meson, cross=i686-w64-mingw32)..."
rm -rf "$DXVK_BUILD/build.32"   # meson setup refuses a dirty build dir
mkdir -p "$DXVK_BUILD"

meson setup "$DXVK_BUILD/build.32" "$DXVK_SRC" \
    --cross-file "$DXVK_SRC/build-win32.txt" \
    --buildtype "release" \
    --prefix "$DXVK_STAGE" \
    --strip \
    --bindir "x32" \
    --libdir "x32" \
    -Dbuild_id=false

log "Compiling DXVK x86 (this takes ~6 min)..."
ninja -C "$DXVK_BUILD/build.32" install

# ---- 4. Collect the DLLs ----------------------------------------------------
DXVK_DLL_OUT="$PREBUILT_DIR/arm64-v8a/dxvk-wine-dlls"
mkdir -p "$DXVK_DLL_OUT"
FOUND=0
for dll in d3d8 d3d9 d3d10core d3d11 dxgi d3dcompiler_47; do
    src="$DXVK_STAGE/x32/$dll.dll"
    if [[ -f "$src" ]]; then
        cp -f "$src" "$DXVK_DLL_OUT/"
        log "  installed: $dll.dll (x86)"
        FOUND=$((FOUND+1))
    else
        # d3d10core + d3dcompiler_47 may legitimately be absent in a
        # given DXVK release — warn but don't fail.
        warn "missing $dll.dll from DXVK build (may be normal for this release)"
    fi
done
if [[ "$FOUND" -eq 0 ]]; then
    die "no DXVK DLLs were produced — check the meson log at $DXVK_BUILD/build.32/meson-logs/"
fi

log_ok "DXVK built: $FOUND DLL(s) staged at $DXVK_DLL_OUT"
print_banner "DXVK build complete ✓"
