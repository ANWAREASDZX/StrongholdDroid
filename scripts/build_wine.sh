#!/usr/bin/env bash
#
# build_wine.sh — cross-compile Wine 9.0 for Android arm64-v8a.
#
# StrongholdDroid uses Wine for the Windows API layer (kernel32, user32,
# ddraw, dsound, dinput8, ...). Box64 handles the x86_64 instruction
# translation; Wine handles the API surface.
#
# Wine on Android is unusual because:
#   1. There is no X server; we use the "android" display driver that
#      paints into a SurfaceView's Surface via ANativeWindow_lock.
#   2. PulseAudio is statically linked against libpulse.so we ship (no
#      daemon); the AudioBridge on the Kotlin side owns the sink end of
#      the FIFO and forwards to AAudio.
#   3. The MinGW-built PE binaries (d3d9.dll, ddraw.dll, dinput8.dll) are
#      built separately by build_dxvk.sh; here we only build the ELF
#      wine64 binary and libwine.so.
#
# Total build time on a 16-core box: ~35 min. Disk space: ~1.2 GB.
#
set -euo pipefail

source "$(dirname "$0")/lib/common.sh"
print_banner "Building Wine 9.0 for Android arm64-v8a"

setup_android_env

WINE_VERSION="${WINE_VERSION:-9.0}"
WINE_SRC="${WINE_SRC:-$BUILD_DIR/wine-$WINE_VERSION}"
WINE_BUILD="$BUILD_DIR/wine-android-arm64"
WINE_OUT="$PREBUILT_DIR/arm64-v8a"

mkdir -p "$WINE_OUT" "$WINE_BUILD"

# ---- Fetch source -----------------------------------------------------------
if [[ ! -d "$WINE_SRC/.git" ]]; then
    log "Cloning Wine $WINE_VERSION..."
    git clone --depth 1 --branch "wine-$WINE_VERSION" \
        https://gitlab.winehq.org/wine/wine.git "$WINE_SRC"
fi

# ---- Android-specific patches ----------------------------------------------
apply_patches() {
    local pdir="$ROOT_DIR/scripts/patches/wine"
    if [[ -d "$pdir" ]]; then
        for p in "$pdir"/*.patch; do
            log "  applying patch: $(basename "$p")"
            ( cd "$WINE_SRC" && git apply --whitespace=fix "$p" || warn "patch failed: $p" )
        done
    fi
}
apply_patches

# ---- Configure -------------------------------------------------------------
# We disable everything we don't need for SC:
#   • No X11 / Wayland / GTK — we use the android display driver
#   • No CUPS / SANE / libcapi20 — printing/scanning not needed
#   • No OSMesa / Vulkan-loader (we ship our own vulkan)
#   • Static libpulse linking
#
# IMPORTANT: Wine's configure REQUIRES --with-wine-tools=DIR when
# cross-compiling.  The "wine tools" (winebuild, wrc, wmc, widl, etc.)
# must be BUILT NATIVELY for the build host (x86_64 Linux) and then
# used during cross-compilation.  Without this, configure fails with:
#   configure: error: you must use the --with-wine-tools option when
#   cross-compiling.
#
# Step A: build the wine TOOLS natively for the build host.
WINE_NATIVE_BUILD="$BUILD_DIR/wine-native-x86_64"
if [[ ! -x "$WINE_NATIVE_BUILD/tools/winebuild/winebuild" ]]; then
    log "Building native Wine tools (host x86_64)..."
    mkdir -p "$WINE_NATIVE_BUILD"
    cd "$WINE_NATIVE_BUILD"
    # Use CC=gcc explicitly — setup_toolchain.sh prepended the NDK's
    # aarch64-linux-android26-clang to PATH, which would intercept
    # configure's `cc` lookup and produce Android binaries (that
    # fail to run on the Linux x86_64 container).
    CC=gcc CXX=g++ \
    "$WINE_SRC/configure" \
        --build="x86_64-pc-linux-gnu" \
        --host="x86_64-pc-linux-gnu" \
        --prefix="$WINE_NATIVE_BUILD/install" \
        --enable-win64 \
        --disable-win16 \
        --without-x \
        --without-wayland \
        --without-opengl \
        --without-vulkan \
        --without-cups \
        --without-sane \
        --without-capi \
        --without-gphoto \
        --without-usb \
        --with-pulse \
        --without-alsa \
        --without-oss \
        --without-coreaudio \
        --without-mingw
    # Build only the tools subdirectory — much faster than full wine.
    # Wine's tools/Makefile has the default target that builds all
    # tool subdirs (winebuild/winebuild, wrc/wrc, wmc/wmc,
    # widl/widl, winegcc/winegcc).  No specific target name needed —
    # just `make -C tools` builds everything in that subdir.
    # Also: no `make install` — the cross-build with --with-wine-tools
    # uses the binaries in-place at $WINE_NATIVE_BUILD/tools/*/...
    make -j"$(nproc)" -C tools
fi

# Step B: cross-configure for Android using the native tools.
log "Configuring Wine (cross-compile for Android arm64-v8a)..."
cd "$WINE_BUILD"
"$WINE_SRC/configure" \
    --host="aarch64-linux-android" \
    --build="x86_64-pc-linux-gnu" \
    --prefix="$WINE_OUT/usr" \
    --enable-win64 \
    --disable-win16 \
    --with-android \
    --with-android-ndk="$ANDROID_NDK_HOME" \
    --with-android-cpu-arch=arm64-v8a \
    --with-wine-tools="$WINE_NATIVE_BUILD" \
    --without-x \
    --without-wayland \
    --without-opengl \
    --without-vulkan \
    --without-cups \
    --without-sane \
    --without-capi \
    --without-gphoto \
    --without-usb \
    --with-pulse \
    --without-alsa \
    --without-oss \
    --without-coreaudio \
    --without-freetype \
    --without-fontconfig \
    PKG_CONFIG_PATH="$WINE_OUT/lib/pkgconfig" \
    PKG_CONFIG_LIBDIR="$WINE_OUT/lib/pkgconfig" \
    CPPFLAGS="-I$WINE_OUT/include" \
    LDFLAGS="-L$WINE_OUT/lib -static-libstdc++"
# NOTE: do NOT pass --without-mingw here — Wine 9.0 REQUIRES PE
# cross-compilation for ARM64 targets.  The error message is:
#   "PE cross-compilation is required for ARM64, please install
#    clang/llvm-dlltool/lld, or llvm-mingw."
# Dockerfile.build installs mingw-w64 (provides x86_64-w64-mingw32-gcc)
# and the NDK ships lld — both should satisfy Wine's PE cross-compile
# requirements.
#
# --without-freetype / --without-fontconfig: cross-compile for Android
# can't see the host's freetype/fontconfig dev files (and Android
# doesn't ship them either).  Fonts will be loaded from the device's
# /system/fonts at runtime via Android's Typeface.
#
# PKG_CONFIG_LIBDIR: overrides pkg-config's default search path so
# ONLY our $WINE_OUT/lib/pkgconfig is consulted.  Without this,
# pkg-config falls back to the host's /usr/lib/x86_64-linux-gnu/pkgconfig
# and finds Ubuntu's libpulse.pc (x86_64) instead of our stub (arm64) —
# the cross-compiler then tries to link x86_64 libpulse.so and fails.

log "Compiling Wine (this takes ~25 min on a 16-core box)..."
make -j"$(nproc)"

log "Installing to $WINE_OUT..."
make install

# ---- Stage the binaries the app expects ------------------------------------
log "Staging artifacts..."
install_lib "$WINE_OUT/usr/bin/wine64"         "libwine/wine64"
install_lib "$WINE_OUT/usr/bin/wineserver"     "libwine/wineserver"
install_lib "$WINE_OUT/usr/lib/libwine.so"     "libwine.so"
install_lib "$WINE_OUT/usr/lib/libwine.so.1"   "libwine.so.1"

# The DLLs that ship inside the WINEPREFIX's system32 are kept separate
# so EnvironmentBuilder.ensureWinePrefix() can stage them at runtime.
mkdir -p "$WINE_OUT/wine_dlls"
cp -f "$WINE_OUT/usr/lib/wine/x86_64-windows/"*.dll "$WINE_OUT/wine_dlls/" 2>/dev/null || true
cp -f "$WINE_OUT/usr/lib/wine/x86_64-unix/"*.so "$WINE_OUT/wine_dlls/" 2>/dev/null || true

log_ok "Wine built: wine64 ($(file "$WINE_OUT/libwine/wine64"))"
print_banner "Wine build complete ✓"
