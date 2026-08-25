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
log "Configuring Wine..."
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
    LDFLAGS="-L$WINE_OUT/lib -static-libstdc++"

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
